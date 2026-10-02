package app.platform.identity.internal;

import app.platformapi.ApiException;

/**
 * Cleans the name an administrator types for a person they invite. The text is typed by a person, so it is kept plain:
 * surrounding space is removed and control characters are refused (they could forge a line in a log or a mail). An
 * empty name means "the person chooses it themselves".
 */
final class PersonNames {

    static final int MAX_LENGTH = 200;

    private PersonNames() {
    }

    /** The cleaned name, or null for none. */
    static String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.strip();
        if (clean.length() > MAX_LENGTH) {
            throw ApiException.validation("displayName", "Is too long.");
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                throw ApiException.validation("displayName", "Must not contain control characters.");
            }
        }
        return clean;
    }
}
