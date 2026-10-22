package app.platform.data.internal;

import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The closed language a number is written in (FA2A, ADR-0176, ADR-0184). A format is literal text, the stored value of
 * a field of the record, and one counter:
 *
 * <pre>{@code WI/{field:subCategory}/{seq:2}   gives   WI/PRD/03}</pre>
 *
 * <ul>
 * <li>{@code {field:<apiName>}} is the stored value of a short-code picklist or a text field; the value must match
 * {@code [A-Za-z0-9_-]{1,10}} when the number is made;</li>
 * <li>{@code {seq:N}} is the counter, 1 to 9 digits, padded with zeros; there is one, or none when the format has at
 * least one {@code {field:...}} (a number the author's own typed code decides, FA4, ADR-0188: the unique index of the
 * number field is then the only thing that keeps two numbers apart);</li>
 * <li>anything else is literal text from {@code [A-Za-z0-9/_-]}, at most 10 characters in all;</li>
 * <li>at most four {@code {...}} parts and 100 characters in all.</li>
 * </ul>
 *
 * <p>There is no clock, no arithmetic, no expression and no code: a format is parsed once into parts and each part is
 * one of three kinds. A mistake is reported as {@link IllegalArgumentException} that names the number of the part and
 * what is wrong with it, never the text of the format.
 */
final class NumberFormat {

    /** What a value must look like to be a part of a number. */
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,10}");
    static final int MAX_PLACEHOLDERS = 4;
    static final int MAX_LITERAL = 10;
    static final int MAX_LENGTH = 100;
    static final int MAX_WIDTH = 9;
    /** How many digits the counter may grow to beyond its width before a text field would be too short. */
    static final int COUNTER_DIGITS = 12;

    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9/_-]+");
    private static final Pattern FIELD = Pattern.compile("field:([A-Za-z][A-Za-z0-9_]{0,59})");
    private static final Pattern SEQUENCE = Pattern.compile("seq:([1-9])");

    /** One part of a format. */
    sealed interface Part permits Literal, Field, Sequence {
    }

    /** Fixed text. */
    record Literal(String text) implements Part {
    }

    /** The stored value of a field of the record. */
    record Field(String apiName) implements Part {
    }

    /** The counter, padded with zeros to the width. */
    record Sequence(int width) implements Part {
    }

    private final List<Part> parts;

    private NumberFormat(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * Reads a format.
     *
     * @throws IllegalArgumentException with the number of the part and the rule it breaks, never the text itself
     */
    static NumberFormat parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("the format is 1 to " + MAX_LENGTH + " characters");
        }
        List<Part> parts = new ArrayList<>();
        int placeholders = 0;
        int sequences = 0;
        int literalCharacters = 0;
        int at = 0;
        int number = 0;
        while (at < text.length()) {
            number++;
            if (text.charAt(at) == '{') {
                int end = text.indexOf('}', at);
                if (end < 0) {
                    throw new IllegalArgumentException("part " + number + " is not closed");
                }
                String body = text.substring(at + 1, end);
                var field = FIELD.matcher(body);
                var sequence = SEQUENCE.matcher(body);
                if (field.matches()) {
                    parts.add(new Field(field.group(1)));
                } else if (sequence.matches()) {
                    parts.add(new Sequence(Integer.parseInt(sequence.group(1))));
                    sequences++;
                } else {
                    throw new IllegalArgumentException("part " + number + " is neither {field:name} nor {seq:N} "
                            + "with N from 1 to " + MAX_WIDTH);
                }
                placeholders++;
                at = end + 1;
            } else {
                int end = at;
                while (end < text.length() && text.charAt(end) != '{') {
                    end++;
                }
                String literal = text.substring(at, end);
                if (!LITERAL.matcher(literal).matches()) {
                    throw new IllegalArgumentException("part " + number + " has a character that is not a letter, "
                            + "digit, slash, hyphen or underscore");
                }
                literalCharacters += literal.length();
                parts.add(new Literal(literal));
                at = end;
            }
        }
        if (placeholders > MAX_PLACEHOLDERS) {
            throw new IllegalArgumentException("the format has more than " + MAX_PLACEHOLDERS + " parts in braces");
        }
        if (sequences > 1) {
            throw new IllegalArgumentException("the format has at most one {seq:N}");
        }
        if (sequences == 0 && placeholders == 0) {
            // Without a counter only the record's own values tell two numbers apart (FA4, ADR-0188).
            throw new IllegalArgumentException("the format has a {seq:N} or at least one {field:name}");
        }
        if (literalCharacters > MAX_LITERAL) {
            throw new IllegalArgumentException("the fixed text is at most " + MAX_LITERAL + " characters in all");
        }
        return new NumberFormat(parts);
    }

    /** The parts in order. */
    List<Part> parts() {
        return parts;
    }

    /** Whether the format has a counter; one without is made of the record's values alone. */
    boolean hasCounter() {
        return parts.stream().anyMatch(Sequence.class::isInstance);
    }

    /** The API names of the fields the format reads, in order. */
    List<String> fields() {
        return parts.stream().filter(Field.class::isInstance).map(part -> ((Field) part).apiName()).toList();
    }

    /** The fewest characters of the field that holds the number so that no number is ever too long for it. */
    int longestNumber() {
        int length = 0;
        for (Part part : parts) {
            length += switch (part) {
                case Literal literal -> literal.text().length();
                case Field ignored -> 10;
                case Sequence sequence -> Math.max(sequence.width(), COUNTER_DIGITS);
            };
        }
        return length;
    }

    /**
     * The parts the record gives, in order, for the scope of the counter: the value of each field the format reads.
     *
     * @throws NumberSeriesException when a value is missing or not a short code
     */
    List<String> scopeParts(Map<String, Object> values) {
        List<String> found = new ArrayList<>();
        for (String name : fields()) {
            found.add(codeOf(values.get(name)));
        }
        return found;
    }

    /** The format written with the values of the record, up to the counter (not including it). */
    String prefix(Map<String, Object> values) {
        StringBuilder text = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Literal literal -> text.append(literal.text());
                case Field field -> text.append(codeOf(values.get(field.apiName())));
                case Sequence ignored -> {
                    return text.toString();
                }
            }
        }
        return text.toString();
    }

    /** The number: every part written, the counter padded with zeros and never cut. */
    String render(Map<String, Object> values, long counter) {
        StringBuilder text = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Literal literal -> text.append(literal.text());
                case Field field -> text.append(codeOf(values.get(field.apiName())));
                case Sequence sequence -> {
                    String digits = Long.toString(counter);
                    text.append("0".repeat(Math.max(0, sequence.width() - digits.length()))).append(digits);
                }
            }
        }
        return text.toString();
    }

    /** A value that can be a part of a number. */
    static String codeOf(Object value) {
        if (value == null || value instanceof String text && text.isEmpty()) {
            throw new NumberSeriesException(Reason.PART_MISSING);
        }
        if (!(value instanceof String text) || !CODE.matcher(text).matches()) {
            throw new NumberSeriesException(Reason.PART_NOT_A_CODE);
        }
        return text;
    }
}
