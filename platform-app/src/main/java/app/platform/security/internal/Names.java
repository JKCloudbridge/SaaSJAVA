package app.platform.security.internal;

import app.platformapi.ApiException;

/**
 * Cleans the text a person types for a name or a description of a profile, an access policy or a role. The text is
 * chosen by the organization, so it is kept plain: surrounding space is removed and control characters are refused
 * (they could forge a line in a log or a mail). Length limits are checked by the request records and by the database.
 */
final class Names {

    private Names() {
    }

    static String name(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            throw ApiException.validation("name", "Must not be empty.");
        }
        requirePlain("name", clean);
        return clean;
    }

    static String description(String text) {
        String clean = text == null ? "" : text.strip();
        requirePlain("description", clean);
        return clean;
    }

    private static void requirePlain(String field, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw ApiException.validation(field, "Must not contain control characters.");
            }
        }
    }
}
