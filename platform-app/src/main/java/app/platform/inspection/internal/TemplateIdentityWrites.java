package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.data.NumberAssignments;
import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.RecordWriteRule;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Identity may change until first approval; every real allocation uses the platform's one number-series door. */
@Component
class TemplateIdentityWrites implements RecordWriteRule {
    static final Set<String> IDENTITY = Set.of(Templates.CATEGORY, Templates.SUB_CATEGORY,
            Templates.PART_CODE, Templates.PLAN_YEAR);
    static final String FIXED = "The identity fields are fixed after the first approval.";
    private final NumberSeries numbers;
    private final NumberAssignments assignments;

    TemplateIdentityWrites(NumberSeries numbers, NumberAssignments assignments) {
        this.numbers = numbers;
        this.assignments = assignments;
    }

    @Override
    public String object() {
        return Templates.OBJECT;
    }

    @Override
    public Set<String> writes() {
        return Set.of(Templates.NUMBER);
    }

    @Override
    public String uniqueWords(String field) {
        return Templates.NUMBER.equals(field) ? "Another template already has the number this one would get. "
                + "Change its part code or identity sub-category (Edit), then approve again." : null;
    }

    @Override
    public String lockedWords() {
        return FIXED;
    }

    @Override
    public Set<String> lockedFields(DataRecord current) {
        return assignments.fixed(Templates.SERIES, current.id(), text(current)) ? IDENTITY : Set.of();
    }

    @Override
    public Changes prepare(DataRecord current, Map<String, Object> merged, Set<String> editable, boolean platform) {
        Map<String, Object> held = current == null ? Map.of() : current.fields();
        boolean changed = IDENTITY.stream().anyMatch(name -> !Objects.equals(held.get(name), merged.get(name)));
        if (current != null && !lockedFields(current).isEmpty()) {
            if (changed || !Objects.equals(held.get(Templates.NUMBER), merged.get(Templates.NUMBER))) {
                throw ApiException.validation("fields", FIXED);
            }
            return Changes.none();
        }
        // The approval engine's narrow number write must not allocate twice. Its hook fixes the reservation.
        if (platform) {
            return Changes.none();
        }
        if (!platform && !editable.contains(Templates.CATEGORY)) {
            return Changes.none();
        }
        if (Templates.isBlank(merged.get(Templates.CATEGORY))) {
            return clearNumber(held);
        }
        var missing = numbers.missingParts(Templates.SERIES, merged);
        if (missing.isEmpty()) {
            throw ApiException.validation(Templates.CATEGORY,
                    "This identity category has no numbering yet. Ask your administrator.");
        }
        if (!missing.get().isEmpty()) {
            return clearNumber(held);
        }
        if (!Templates.isBlank(merged.get(Templates.PLAN_YEAR)) && !Templates.isYear(merged.get(Templates.PLAN_YEAR))) {
            throw ApiException.validation(Templates.PLAN_YEAR, "The plan year is four digits, for example 2026.");
        }
        if (!Templates.isBlank(held.get(Templates.NUMBER))
                && assignments.sameInputs(Templates.SERIES, held, merged)) {
            return Changes.none();
        }
        try {
            numbers.known(Templates.SERIES, merged).ifPresent(number ->
                    assignments.checkAvailable(Templates.SERIES, current == null ? null : current.id(), number));
            return new Changes(Map.of(Templates.NUMBER, numbers.next(Templates.SERIES, merged)), List.of());
        } catch (NumberSeriesException unavailable) {
            if (unavailable.reason() == NumberSeriesException.Reason.NUMBER_TAKEN) {
                throw ApiException.validation(Templates.NUMBER, uniqueWords(Templates.NUMBER));
            }
            throw ApiException.validation("fields", "This template cannot get a number yet: check its identity "
                    + "category, sub-category, part code and plan year (Edit), then approve again.");
        }
    }

    @Override
    public void stored(DataRecord before, DataRecord after) {
        String number = text(after);
        if (number != null && (before == null || !number.equals(text(before)))) {
            try {
                assignments.reserve(Templates.SERIES, Templates.OBJECT, after.id(), number, false);
            } catch (NumberSeriesException taken) {
                throw ApiException.validation(Templates.NUMBER, uniqueWords(Templates.NUMBER));
            }
        }
    }

    private static Changes clearNumber(Map<String, Object> held) {
        return Templates.isBlank(held.get(Templates.NUMBER)) ? Changes.none()
                : new Changes(Map.of(), List.of(Templates.NUMBER));
    }

    private static String text(DataRecord record) {
        return record.fields().get(Templates.NUMBER) instanceof String number ? number : null;
    }
}
