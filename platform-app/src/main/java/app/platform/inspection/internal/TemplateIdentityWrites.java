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
    static final Set<String> IDENTITY = Set.of(Templates.CATEGORY, Templates.PART_A, Templates.PART_B,
            Templates.SERIAL);
    static final String STARTED = "This combination has just started; its numbers are now automatic. Save again.";
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
                + "Change its identification, Part A or Part B (Edit), then approve again." : null;
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
        rejectUnusablePart(Templates.PART_A, "Part A", merged);
        rejectUnusablePart(Templates.PART_B, "Part B", merged);
        var missing = numbers.missingParts(Templates.SERIES, merged);
        if (missing.isEmpty()) {
            throw ApiException.validation(Templates.CATEGORY,
                    "This identification has no numbering yet. Ask your administrator.");
        }
        if (!missing.get().isEmpty()) {
            return clearNumber(held);
        }
        Object typed = merged.get(Templates.SERIAL);
        if (!Templates.isBlank(typed) && !Templates.isSerial(typed)) {
            throw ApiException.validation(Templates.SERIAL, "The serial number is digits only, for example 5.");
        }
        if (!Templates.isBlank(held.get(Templates.NUMBER))
                && assignments.sameInputs(Templates.SERIES, held, merged)) {
            return Changes.none();
        }
        try {
            numbers.known(Templates.SERIES, merged).ifPresent(number ->
                    assignments.checkAvailable(Templates.SERIES, current == null ? null : current.id(), number));
            return new Changes(Map.of(Templates.NUMBER, take(merged, typed)), List.of());
        } catch (NumberSeriesException unavailable) {
            switch (unavailable.reason()) {
                case NUMBER_TAKEN -> throw ApiException.validation(Templates.NUMBER, uniqueWords(Templates.NUMBER));
                case ALREADY_STARTED -> throw ApiException.validation(Templates.SERIAL, STARTED);
                default -> throw ApiException.validation("fields", "This template cannot get a number yet: check its "
                        + "identification, Part A and Part B (Edit), then approve again.");
            }
        }
    }

    /**
     * The number of a combination: the serial the person typed starts a combination that has no counter, anything else
     * takes the next one. A serial typed for a combination that already has numbers is not used (the screen shows it
     * disabled), so a stale value can never skip numbers.
     */
    private String take(Map<String, Object> merged, Object typed) {
        boolean starts = !Templates.isBlank(typed) && numbers.plan(Templates.SERIES, merged)
                .map(plan -> plan.counted() && plan.next().isEmpty()).orElse(false);
        return starts ? numbers.start(Templates.SERIES, merged, Long.parseLong((String) typed))
                : numbers.next(Templates.SERIES, merged);
    }

    /** A part is typed freehand; the server accepts capital letters, digits and a hyphen (1 to 10) and nothing else. */
    private static void rejectUnusablePart(String field, String label, Map<String, Object> merged) {
        Object value = merged.get(field);
        if (!Templates.isBlank(value) && !Templates.isPart(value)) {
            throw ApiException.validation(field, label + " uses capital letters, digits or a hyphen, up to 10.");
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
