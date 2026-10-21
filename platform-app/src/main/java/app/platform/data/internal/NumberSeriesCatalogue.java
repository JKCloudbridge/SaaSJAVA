package app.platform.data.internal;

import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import app.platform.sharedkernel.solution.NumberSeriesDeclarations;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The number series of this release, collected from the shipped solutions and checked once, when the application
 * starts (FA2A, ADR-0184). A mistake in a declaration stops the start with a message that names the series and the part
 * and never repeats a text, so it is found by the build and never by an organization:
 *
 * <ul>
 * <li>the object is one the platform defines and keeps its records itself;</li>
 * <li>the field of the number is a unique text field only the platform writes, long enough for the longest number;</li>
 * <li>the selector, when there is one, is a short-code picklist, and every format belongs to one of its values;</li>
 * <li>every format is in the closed language ({@link NumberFormat}) and reads only text fields and short-code picklists
 * of the object.</li>
 * </ul>
 *
 * <p>Nothing here changes while the application runs.
 */
@Component
final class NumberSeriesCatalogue {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /**
     * One series, checked.
     *
     * @param key its permanent name
     * @param object the object whose records it numbers
     * @param field the field that holds the number
     * @param selector the picklist whose value chooses the format, or an empty text
     * @param formats the formats by value of the selector (the empty text for a series with one format)
     */
    record Series(String key, String object, String field, String selector, Map<String, NumberFormat> formats) {

        Series {
            formats = Map.copyOf(formats);
        }

        /** The format for a value of the selector, if the series has one. */
        Optional<NumberFormat> formatFor(String selectorValue) {
            return Optional.ofNullable(formats.get(selectorValue));
        }
    }

    private final Map<String, Series> series;

    @Autowired
    NumberSeriesCatalogue(PlatformObjects platform, ObjectProvider<NumberSeriesDeclarations> declarations) {
        this(platform, declarations.orderedStream().flatMap(source -> source.declared().stream()).toList());
    }

    /** The series of these declarations, checked (the tests hand it any combination). */
    NumberSeriesCatalogue(PlatformObjects platform, List<NumberSeriesDeclaration> declarations) {
        Map<String, Series> checked = new LinkedHashMap<>();
        for (NumberSeriesDeclaration declaration : declarations) {
            Series one = check(platform, declaration);
            if (checked.put(one.key(), one) != null) {
                throw new IllegalStateException("number series '" + one.key() + "' is declared twice");
            }
        }
        this.series = Map.copyOf(checked);
    }

    /** The series with the key. */
    Optional<Series> series(String key) {
        return Optional.ofNullable(series.get(key));
    }

    /** Every series, by key. */
    Map<String, Series> all() {
        return series;
    }

    // ---- checking a declaration ----

    private static Series check(PlatformObjects platform, NumberSeriesDeclaration declaration) {
        String where = "number series '" + declaration.key() + "'";
        if (!KEY.matcher(declaration.key()).matches()) {
            throw new IllegalStateException("a number series has a key that is not lower case letters, digits and "
                    + "hyphens");
        }
        ObjectDefinition object = platform.named(declaration.object()).orElseThrow(() -> new IllegalStateException(
                where + ": the object is not defined by the platform"));
        if (object.managedBy() != null) {
            throw new IllegalStateException(where + ": the object's records are kept by another part of the platform");
        }
        FieldDefinition numberField = object.field(declaration.field()).orElseThrow(() -> new IllegalStateException(
                where + ": the field of the number is not a field of the object"));
        if (numberField.type() != FieldType.TEXT || !numberField.unique() || !numberField.platformWritten()
                || numberField.retired()) {
            throw new IllegalStateException(where + ": the field of the number is a unique text field only the "
                    + "platform writes");
        }
        Map<String, NumberFormat> formats = formats(where, declaration, object);
        int longest = formats.values().stream().mapToInt(NumberFormat::longestNumber).max().orElse(0);
        if (((TextConfiguration) numberField.configuration()).maxLength() < longest) {
            throw new IllegalStateException(where + ": the field of the number is shorter than the longest number "
                    + "(" + longest + " characters)");
        }
        for (Map.Entry<String, NumberFormat> entry : formats.entrySet()) {
            checkFields(where, object, numberField, entry.getValue());
        }
        return new Series(declaration.key(), declaration.object(), declaration.field(), declaration.selector(),
                formats);
    }

    private static Map<String, NumberFormat> formats(String where, NumberSeriesDeclaration declaration,
            ObjectDefinition object) {
        if (declaration.formats().isEmpty()) {
            throw new IllegalStateException(where + ": there is no format");
        }
        Set<String> choices = new HashSet<>();
        if (declaration.selector().isEmpty()) {
            if (declaration.formats().size() != 1 || !declaration.formats().containsKey("")) {
                throw new IllegalStateException(where + ": a series with no selector has one format");
            }
        } else {
            FieldDefinition selector = object.field(declaration.selector()).orElseThrow(
                    () -> new IllegalStateException(where + ": the selector is not a field of the object"));
            if (selector.type() != FieldType.PICKLIST || !selector.codeValues() || selector.retired()) {
                throw new IllegalStateException(where + ": the selector is a short-code picklist");
            }
            ((PicklistConfiguration) selector.configuration()).values().stream().map(PicklistValue::value)
                    .forEach(choices::add);
        }
        Map<String, NumberFormat> formats = new TreeMap<>();
        int position = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(declaration.formats()).entrySet()) {
            position++;
            if (!declaration.selector().isEmpty() && !choices.contains(entry.getKey())) {
                throw new IllegalStateException(where + ": format " + position + " belongs to a value the selector "
                        + "does not have");
            }
            try {
                formats.put(entry.getKey(), NumberFormat.parse(entry.getValue()));
            } catch (IllegalArgumentException mistake) {
                throw new IllegalStateException(where + ", format " + position + ": " + mistake.getMessage());
            }
        }
        return formats;
    }

    private static void checkFields(String where, ObjectDefinition object, FieldDefinition numberField,
            NumberFormat format) {
        for (String name : format.fields()) {
            FieldDefinition field = object.field(name).orElseThrow(() -> new IllegalStateException(
                    where + ": a format reads a field the object does not have"));
            boolean code = field.type() == FieldType.PICKLIST && field.codeValues();
            if (field.apiName().equals(numberField.apiName()) || field.retired()
                    || !(code || field.type() == FieldType.TEXT)) {
                throw new IllegalStateException(where + ": a format reads a field that is not a text field or a "
                        + "short-code picklist in use");
            }
        }
    }
}
