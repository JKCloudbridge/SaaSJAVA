package app.platform.metadata;

import java.util.List;

/**
 * The settings that belong to one field type (ADR-0060). One field type has exactly one configuration shape, and the
 * shapes are a closed set, so code that handles a field switches over them and the compiler says when a type was
 * forgotten. This is the cleaner alternative to one table with fifty optional columns.
 */
public sealed interface FieldConfiguration {

    /** Text, long text, e-mail, phone and URL: the most characters a value may have. */
    record TextConfiguration(int maxLength) implements FieldConfiguration {
    }

    /** A whole number: the most digits. */
    record NumberConfiguration(int digits) implements FieldConfiguration {
    }

    /** Decimal, currency and percent: digits in all and digits after the point. */
    record DecimalConfiguration(int precision, int scale) implements FieldConfiguration {
    }

    /** Types that take no setting: checkbox, date, date and time, time. */
    record NoConfiguration() implements FieldConfiguration {
    }

    /** The choices of a picklist or multi-picklist, in the order they are offered. */
    record PicklistConfiguration(List<PicklistValue> values) implements FieldConfiguration {

        /** Copies what is given. */
        public PicklistConfiguration {
            values = List.copyOf(values);
        }
    }

    /**
     * A lookup or master-detail: the API name of the object it points to and how the relationship behaves (ADR-0063).
     *
     * @param targetObject the object the field points to (the parent of the relationship)
     * @param onDelete what happens to a record that points at a removed one: {@code CLEAR} or {@code REFUSE} for a
     *        lookup, always {@code CASCADE} for a master-detail
     * @param reparentable whether a detail may be moved to another master (a master-detail only)
     * @param listLabel the label of the list of these records on the parent, or empty for the plural label of the
     *        child object
     */
    record ReferenceConfiguration(String targetObject, DeleteBehaviour onDelete, boolean reparentable,
            String listLabel) implements FieldConfiguration {

        /** A reference with the behaviour of a lookup that was given no setting. */
        public ReferenceConfiguration(String targetObject) {
            this(targetObject, DeleteBehaviour.CLEAR, false, "");
        }

        /** A reference with the behaviour a field of the type has when nobody chose one. */
        public static ReferenceConfiguration defaultsFor(FieldType type, String targetObject) {
            return new ReferenceConfiguration(targetObject,
                    type == FieldType.MASTER_DETAIL ? DeleteBehaviour.CASCADE : DeleteBehaviour.CLEAR, false, "");
        }
    }

    /** A formula: its text and the type of its result. Stored only; nothing is calculated before Sprint 13. */
    record FormulaConfiguration(String expression, FieldType resultType) implements FieldConfiguration {

        /** Prints the result type only: the expression is text a person typed (ADR-0012). */
        @Override
        public String toString() {
            return "FormulaConfiguration[" + resultType + "]";
        }
    }

    /** An auto-number: fixed text in front, the first number and the width it is padded to. */
    record AutoNumberConfiguration(String prefix, long startAt, int width) implements FieldConfiguration {
    }
}
