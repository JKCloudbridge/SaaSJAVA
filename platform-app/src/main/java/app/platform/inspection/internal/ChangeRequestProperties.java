package app.platform.inspection.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the change requests on a template (FA5, ADR-0191). Every limit is checked by the application under the
 * lock of the template, so two requests arriving together cannot both pass the last free place.
 *
 * @param maxCommentsPerRequest the comments one request may hold (50)
 * @param maxOpenPerPerson the open requests one person may have on one template (10)
 * @param maxPerTemplate the requests one template may have for good, since none is ever deleted (200)
 * @param maxCommentChars the characters of a comment (2,000)
 * @param maxTitleChars the characters of the title of a request (120)
 * @param maxNoteChars the characters of the reason for declining (500)
 * @param maxFirstComments the comments a request may be made with (10)
 * @param maxTurnedAtOnce the requests that may be turned into one version together (20)
 */
@ConfigurationProperties("platform.inspection.change-requests")
record ChangeRequestProperties(@DefaultValue("50") int maxCommentsPerRequest, @DefaultValue("10") int maxOpenPerPerson,
        @DefaultValue("200") int maxPerTemplate, @DefaultValue("2000") int maxCommentChars,
        @DefaultValue("120") int maxTitleChars, @DefaultValue("500") int maxNoteChars,
        @DefaultValue("10") int maxFirstComments, @DefaultValue("20") int maxTurnedAtOnce) {

    ChangeRequestProperties {
        range("max-comments-per-request", maxCommentsPerRequest, 1, 1_000);
        range("max-open-per-person", maxOpenPerPerson, 1, 1_000);
        range("max-per-template", maxPerTemplate, 1, 100_000);
        // The database's own ceilings are higher (4,000 characters for a comment, 200 for a title, 1,000 for a reason).
        range("max-comment-chars", maxCommentChars, 1, 4_000);
        range("max-title-chars", maxTitleChars, 1, 200);
        range("max-note-chars", maxNoteChars, 1, 1_000);
        range("max-first-comments", maxFirstComments, 0, 100);
        range("max-turned-at-once", maxTurnedAtOnce, 1, 200);
    }

    /** The caps of the product, for tests and callers that need no setting. */
    static ChangeRequestProperties standard() {
        return new ChangeRequestProperties(50, 10, 200, 2_000, 120, 500, 10, 20);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.inspection.change-requests." + name + " must be " + least
                    + " to " + most);
        }
    }
}
