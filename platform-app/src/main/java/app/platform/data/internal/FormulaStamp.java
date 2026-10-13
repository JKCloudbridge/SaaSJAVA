package app.platform.data.internal;

import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The fingerprint of an object's formulas (Sprint 16A, ADR-0113): what the stored results of its records are current
 * <em>for</em>. It covers the API name, the result type and the text of every formula field that is not retired, in the
 * order of the API names, so editing one formula, adding one or removing one changes it, and changing a label or any
 * other field does not. It is computed from the published metadata alone, so it needs no event and no database, and
 * two instances always agree.
 *
 * <p>The text of a formula is what an organization typed; only a hash of it leaves this class, and the hash is never
 * logged or audited.
 */
final class FormulaStamp {

    /** The stamp of an object without formulas. */
    static final String NONE = "";

    private static final int LENGTH = 32;

    private FormulaStamp() {
    }

    /** The fingerprint of the formulas of the object, or {@link #NONE} when it has none. */
    static String of(ObjectDefinition object) {
        List<String> lines = object.fields().stream()
                .filter(field -> field.type() == FieldType.FORMULA && !field.retired()
                        && field.configuration() instanceof FormulaConfiguration)
                .sorted(Comparator.comparing(FieldDefinition::apiName))
                .map(field -> field.apiName() + "|" + ((FormulaConfiguration) field.configuration()).resultType()
                        + "|" + ((FormulaConfiguration) field.configuration()).expression())
                .toList();
        if (lines.isEmpty()) {
            return NONE;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java release", e);
        }
    }
}
