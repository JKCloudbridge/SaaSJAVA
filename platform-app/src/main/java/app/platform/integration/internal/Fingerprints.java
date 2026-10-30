package app.platform.integration.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fingerprint of the parts of a definition that decide whether a successful test still means something: the type,
 * the address, how it authenticates and the settings (ADR-0210). The same definition always gives the same text, and
 * any difference in these parts gives another. Every part is length-prefixed, so no two different definitions can write
 * the same bytes. The credential <em>name</em> is part of it; the credential is not available here at all.
 */
final class Fingerprints {

    private Fingerprints() {
    }

    static String of(String connectorType, int connectorVersion, String baseUrl, String authKind,
            String authHeaderName, String credentialName, Map<String, String> settings) {
        StringBuilder text = new StringBuilder();
        part(text, connectorType);
        part(text, Integer.toString(connectorVersion));
        part(text, baseUrl);
        part(text, authKind);
        part(text, authHeaderName == null ? "-" : "h:" + authHeaderName.toLowerCase(java.util.Locale.ROOT));
        part(text, credentialName == null ? "-" : "c:" + credentialName);
        new TreeMap<>(settings).forEach((key, value) -> {
            part(text, key);
            part(text, value);
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    private static void part(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value).append(';');
    }
}
