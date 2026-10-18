package app.platform.sharedkernel.notice;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeText.Problem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoticeTextTest {

    private static List<String> places(List<Problem> problems) {
        return problems.stream().map(Problem::place).toList();
    }

    @Test
    void aPlainTextWithPlacholdersOfItsKindIsAccepted() {
        assertThat(NoticeText.validate(NoticeKind.APPROVAL_TASK, "Waiting for you",
                "A record of {object} waits in {process}, step {step}, of {organization}.")).isEmpty();
    }

    @Test
    void everyKindUsesTheOrganizationAndTheObjectAndItsOwnFacts() {
        assertThat(NoticeText.placeholders(NoticeKind.WORKFLOW_NOTICE)).containsExactly("object", "organization",
                "workflow");
        assertThat(NoticeText.placeholders(NoticeKind.APPROVAL_DECIDED)).containsExactly("object", "organization",
                "outcome", "process");
        // The stage of a trial is a fact, but not something a text may name: the trial text is the platform's own.
        assertThat(NoticeText.placeholders(NoticeKind.TRIAL_NOTICE)).containsExactly("object", "organization");
    }

    @Test
    void aPlaceholderThatIsNotOnTheListOfTheKindIsRefusedAndTheMessageNamesTheList() {
        List<Problem> problems = NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "Look at {process} and {title}.");

        assertThat(places(problems)).containsExactly("body", "body");
        assertThat(problems.get(0).message()).contains("{process}").contains("{workflow}");
        assertThat(problems.get(1).message()).contains("{title}");
    }

    @Test
    void anAValueOfARecordIsNeverAPlaceholder() {
        for (String field : List.of("{amount}", "{name}", "{title}", "{recordTitle}", "{fieldA__c}")) {
            assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "See " + field)).as(field).isNotEmpty();
        }
    }

    @Test
    void theLengthsAreBounded() {
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "x".repeat(NoticeText.TITLE_MAX), "ok")).isEmpty();
        assertThat(places(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "x".repeat(NoticeText.TITLE_MAX + 1), "ok")))
                .containsExactly("title");
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "x".repeat(NoticeText.BODY_MAX))).isEmpty();
        assertThat(places(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "x".repeat(NoticeText.BODY_MAX + 1))))
                .containsExactly("body");
    }

    @Test
    void anEmptyOrBlankTextIsRefusedAtItsPlace() {
        assertThat(places(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "", " "))).containsExactly("title", "body");
        assertThat(places(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, null, null)))
                .containsExactly("title", "body");
    }

    @Test
    void noMarkupNoLinkNoAddressAndNoControlCharacters() {
        for (String body : List.of("<b>hi</b>", "a > b", "see https://elsewhere.example.test/",
                "go to www.example.test", "write to user-a@example.test", "ftp://x", "mailto me", "bell\u0007")) {
            assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", body)).as(body).isNotEmpty();
        }
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "Two lines\nare allowed in a body")).isEmpty();
        assertThat(places(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "Two\nlines", "ok")))
                .containsExactly("title");
    }

    @Test
    void aBraceThatIsNotAPlaceholderIsAMistake() {
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "Open { brace")).isNotEmpty();
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "Stray } brace")).isNotEmpty();
        assertThat(NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, "T", "Empty {} braces")).isNotEmpty();
    }

    @Test
    void theProblemsNeverRepeatTheText() {
        String secret = "ZQXJ-7731";
        List<Problem> problems = NoticeText.validate(NoticeKind.WORKFLOW_NOTICE, secret + " <",
                secret + " {" + secret + "} see https://x.example.test/" + secret);

        assertThat(problems).isNotEmpty();
        assertThat(problems.stream().map(Problem::message)).noneMatch(message -> message.contains(secret));
    }

    @Test
    void theTextOfAKindThatIsThePlatformsOwnCannotBeWritten() {
        assertThat(NoticeText.validate(NoticeKind.TRIAL_NOTICE, "T", "ok")).hasSize(1);
        assertThat(NoticeKind.TRIAL_NOTICE.writable()).isFalse();
    }

    @Test
    void renderingPutsLabelsInAndLeavesAMissingOneOut() {
        assertThat(NoticeText.render("{object} in {organization}",
                Map.of("object", "Invoice", "organization", "Org A")))
                .isEqualTo("Invoice in Org A");
        assertThat(NoticeText.render("Waits {process} here", Map.of())).isEqualTo("Waits here");
        assertThat(NoticeText.render("Hello {object}!", Map.of("object", ""))).isEqualTo("Hello !");
    }

    @Test
    void anInsertedLabelCanOnlyBeAName() {
        String rendered = NoticeText.render("Look: {object}",
                Map.of("object", "<a href=\"https://elsewhere.example.test/\">Click</a>\n{process}"));

        assertThat(rendered).doesNotContain("<").doesNotContain(">").doesNotContain(":/").doesNotContain("\n")
                .doesNotContain("{");
    }

    @Test
    void theSafeNameIsCutAndKeepsOnlyQuietCharacters() {
        assertThat(NoticeText.safeName("Org & Sons, (A)")).isEqualTo("Org & Sons, (A)");
        assertThat(NoticeText.safeName("a@b.c/d")).isEqualTo("abcd");
        assertThat(NoticeText.safeName("   ")).isEqualTo("an organization");
        assertThat(NoticeText.safeName("x".repeat(500))).hasSize(80);
    }

    @Test
    void everyWritableKindHasAPlatformTextThatPassesItsOwnRules() {
        for (NoticeKind kind : NoticeKind.values()) {
            if (kind.writable()) {
                assertThat(NoticeText.validate(kind, NoticeText.defaultTitle(kind), NoticeText.defaultBody(kind)))
                        .as(kind.name()).isEmpty();
            }
        }
    }
}
