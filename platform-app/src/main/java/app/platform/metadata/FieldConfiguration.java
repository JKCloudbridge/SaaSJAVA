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

    /** A lookup or master-detail: the API name of the object it points to. */
    record ReferenceConfiguration(String targetObject) implements FieldConfiguration {
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
