package app.platform.sheets.internal;

import app.platform.sheets.FillReports;
import app.platform.sheets.SheetStatus;
import app.platform.sheets.internal.FillValues.Kind;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds fill-ins by a value in a reportable column (FF2, ADR-0193).
 *
 * <p>The structure comes from the version the fill-ins are pinned to, which a reader sees only once it is in use or was
 * (the same rule as every read of a version). The condition is checked against the type of the column before any
 * statement is made, with words that never repeat what was typed. The batch is read from the typed table in one
 * statement; one more row than asked for tells whether another batch may follow.
 */
@Service
@Transactional(readOnly = true)
class DefaultFillReports implements FillReports {

    private static final int MOST_VALUES = 100;
    private static final int MOST_BATCH = 200;
    private static final String COLUMN = "Choose one of the columns the template offers for a report.";
    private static final String CONDITION = "The condition does not fit the column.";

    private final SheetVersionStore versions;
    private final FillValueStore values;
    private final SheetProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultFillReports(SheetVersionStore versions, FillValueStore values, SheetProperties properties) {
        this.versions = versions;
        this.values = values;
        this.properties = properties;
    }

    @Override
    public List<ReportColumn> columns(String ownerObject, UUID ownerId, int versionNo) {
        return grid(ownerObject, ownerId, versionNo).map(grid -> FillValues.reportableColumns(grid).stream()
                .map(column -> new ReportColumn(column.id(), column.label(), column.type().json(),
                        column.picklist() == null ? List.of() : column.picklist()))
                .toList()).orElse(List.of());
    }

    @Override
    public Batch find(String fillObject, String ownerObject, UUID ownerId, int versionNo, String columnId,
            Condition condition, UUID after, int limit) {
        if (limit < 1 || limit > MOST_BATCH) {
            throw new IllegalArgumentException("A batch is 1 to " + MOST_BATCH);
        }
        SheetGrid grid = grid(ownerObject, ownerId, versionNo)
                .orElseThrow(() -> ApiException.validation("column", COLUMN));
        Column column = FillValues.reportableColumns(grid).stream().filter(one -> one.id().equals(columnId))
                .findFirst().orElseThrow(() -> ApiException.validation("column", COLUMN));
        Kind kind = kindOf(column.type());
        FillValueStore.Shape shape = check(column, kind, condition);
        String first = shape == FillValueStore.Shape.EQUALS ? condition.values().get(0) : condition.from();
        List<UUID> found = values.find(fillObject, ownerObject, ownerId, versionNo, columnId, kind, shape, first,
                condition.values(), condition.to(), after, limit + 1);
        boolean more = found.size() > limit;
        List<UUID> batch = more ? found.subList(0, limit) : found;
        return new Batch(batch, batch.isEmpty() ? null : batch.get(batch.size() - 1), more);
    }

    // ---- the pieces ----

    private Optional<SheetGrid> grid(String ownerObject, UUID ownerId, int versionNo) {
        return versions.findGrid(ownerObject, ownerId, versionNo)
                .filter(found -> SheetStatus.valueOf(found.row().status()).readableByReaders())
                .map(found -> SheetGridReader.read(json.readTree(found.grid())));
    }

    private static Kind kindOf(ColumnType type) {
        return switch (type) {
            case NUMBER -> Kind.NUMBER;
            case DATE -> Kind.DATE;
            case BOOLEAN -> Kind.BOOLEAN;
            case TEXT, PICKLIST -> Kind.TEXT;
            default -> throw new IllegalArgumentException("Unknown column type");
        };
    }

    /** The shape of the statement the condition asks for, or a refusal in fixed words. */
    private FillValueStore.Shape check(Column column, Kind kind, Condition condition) {
        if (condition == null || condition.operator() == null) {
            throw ApiException.validation("condition", CONDITION);
        }
        return switch (condition.operator()) {
            case EQUALS -> {
                requireValues(column, kind, condition, 1);
                yield FillValueStore.Shape.EQUALS;
            }
            case ONE_OF -> {
                if (kind == Kind.BOOLEAN) {
                    throw ApiException.validation("condition", CONDITION);
                }
                requireValues(column, kind, condition, MOST_VALUES);
                yield FillValueStore.Shape.ONE_OF;
            }
            case BETWEEN -> {
                if (kind != Kind.NUMBER && kind != Kind.DATE || !condition.values().isEmpty()
                        || condition.from() == null && condition.to() == null) {
                    throw ApiException.validation("condition", CONDITION);
                }
                requireFits(column, kind, condition.from());
                requireFits(column, kind, condition.to());
                yield condition.from() == null ? FillValueStore.Shape.TO : condition.to() == null
                        ? FillValueStore.Shape.FROM : FillValueStore.Shape.BOTH;
            }
            default -> throw ApiException.validation("condition", CONDITION);
        };
    }

    private void requireValues(Column column, Kind kind, Condition condition, int most) {
        if (condition.values().isEmpty() || condition.values().size() > most || condition.from() != null
                || condition.to() != null) {
            throw ApiException.validation("condition", CONDITION);
        }
        condition.values().forEach(value -> requireFits(column, kind, value));
    }

    /** A bound that is left out (null) fits; a value must be one the column could hold. */
    private void requireFits(Column column, Kind kind, String value) {
        if (value == null) {
            return;
        }
        boolean fits;
        if (kind == Kind.BOOLEAN) {
            fits = "true".equals(value) || "false".equals(value);
        } else if (kind == Kind.TEXT && column.type() == ColumnType.TEXT) {
            fits = !value.isEmpty() && value.length() <= properties.limits().maxCellChars()
                    && !CellValues.unsafe(value);
        } else {
            fits = CellValues.problem(column, value) == null;
        }
        if (!fits) {
            throw ApiException.validation("condition", CONDITION);
        }
    }
}
