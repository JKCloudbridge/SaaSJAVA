package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.RegisterRules.Checked;
import app.platform.inspection.internal.RegisterRules.Completed;
import app.platform.inspection.internal.RegisterRules.Raised;
import app.platform.inspection.internal.RegisterViews.RegisterCompletion;
import app.platform.inspection.internal.RegisterViews.RegisterInput;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * What the register form accepts (FA8, ADR-0202), with no database: the request number is typed by the requester and is
 * not checked for a duplicate; the bounds of every box; the text rules every text of a change request obeys; days
 * written
 * year-month-day; the two answers; and the title a request is called by.
 */
class RegisterRulesTest {

    private static RegisterInput input(String requestNo, String change) {
        return new RegisterInput(requestNo, change, null, null, null, null);
    }

    private static RegisterCompletion completion(String department, String approved, String effective, String server,
            String master) {
        return new RegisterCompletion(0L, department, null, null, approved, null, effective, server, master);
    }

    @Test
    void aRequestNumberAndWhatIsAskedAreEnoughToRaiseARequest() {
        Checked<Raised> checked = RegisterRules.raise(input("  DCN-0007 ", "  Change the title  "));

        assertThat(checked.accepted()).isTrue();
        assertThat(checked.value().requestNo()).isEqualTo("DCN-0007");
        assertThat(checked.value().changeRequested()).isEqualTo("Change the title");
        assertThat(checked.value().basisForChange()).isNull();
        assertThat(checked.value().obsoletedEffectiveOn()).isNull();
    }

    @Test
    void theRequestNumberIsTypedByHandAndNothingChecksItForADuplicate() {
        // Two requests with the same number are both accepted by this rule: the people keep their numbers themselves.
        assertThat(RegisterRules.raise(input("DCN-1", "A")).accepted()).isTrue();
        assertThat(RegisterRules.raise(input("DCN-1", "B")).accepted()).isTrue();
    }

    @Test
    void theTwoBoxesThatAreRequiredAreNamedWhenTheyAreEmpty() {
        Checked<Raised> checked = RegisterRules.raise(input(" ", ""));

        assertThat(checked.accepted()).isFalse();
        assertThat(checked.problems()).containsOnlyKeys("requestNo", "changeRequested");
        assertThat(checked.problems().get("requestNo")).containsExactly("Write the request number.");
    }

    @Test
    void everyBoxHasItsBound() {
        assertThat(RegisterRules.raise(input("x".repeat(RegisterRules.REQUEST_NO), "a")).accepted()).isTrue();
        assertThat(RegisterRules.raise(input("x".repeat(RegisterRules.REQUEST_NO + 1), "a")).problems())
                .containsKey("requestNo");
        assertThat(RegisterRules.raise(input("n", "x".repeat(RegisterRules.CHANGE_REQUESTED + 1))).problems())
                .containsKey("changeRequested");
        RegisterInput tooLong = new RegisterInput("n", "a", "x".repeat(RegisterRules.BASIS + 1),
                "x".repeat(RegisterRules.HORIZONTAL + 1), "x".repeat(RegisterRules.OBSOLETED + 1), null);
        assertThat(RegisterRules.raise(tooLong).problems()).containsOnlyKeys("basisForChange",
                "horizontalDeployment", "obsoletedFormat");
    }

    @Test
    void markupAndControlCharactersAreRefusedWithoutRepeatingTheText() {
        Checked<Raised> markup = RegisterRules.raise(input("n", "<script>alert(1)</script>"));
        assertThat(markup.problems().get("changeRequested")).hasSize(1);
        assertThat(markup.problems().get("changeRequested").get(0)).doesNotContain("script").contains("<");
        assertThat(RegisterRules.raise(input("a\nb", "ok")).problems()).as("a number is one line")
                .containsKey("requestNo");
        assertThat(RegisterRules.raise(input("n", "two\nlines")).accepted()).as("what is asked may use lines").isTrue();
        assertThat(RegisterRules.raise(input("n", "bell\u0007")).problems()).containsKey("changeRequested");
    }

    @Test
    void theDayThePersonTypesIsReadYearMonthDayAndAnythingElseIsAskedAgain() {
        RegisterInput good = new RegisterInput("n", "a", null, null, "Old format", "2026-12-31");
        assertThat(RegisterRules.raise(good).value().obsoletedEffectiveOn()).isEqualTo(LocalDate.parse("2026-12-31"));
        RegisterInput bad = new RegisterInput("n", "a", null, null, null, "soon");
        assertThat(RegisterRules.raise(bad).problems().get("obsoletedEffectiveOn").get(0))
                .contains("year-month-day").doesNotContain("soon");
    }

    @Test
    void theApproversCompleteTheBottomAndLeaveWhatTheyLike() {
        Checked<Completed> checked = RegisterRules.complete(completion("Plant 1", "user-a, 2026-10-26",
                "2026-11-02", "yes", "NO"));

        assertThat(checked.accepted()).isTrue();
        assertThat(checked.value().department()).isEqualTo("Plant 1");
        assertThat(checked.value().effectiveOn()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(checked.value().serverUpdated()).as("answers are kept in capitals").isEqualTo("YES");
        assertThat(checked.value().masterListUpdated()).isEqualTo("NO");
        Checked<Completed> empty = RegisterRules.complete(completion(null, "", null, "", null));
        assertThat(empty.accepted()).isTrue();
        assertThat(empty.value().approvedBy()).as("a blank box is nothing").isNull();
        assertThat(empty.value().serverUpdated()).isNull();
    }

    @Test
    void aWrongAnswerOrDayInTheBottomNamesTheBox() {
        Checked<Completed> checked = RegisterRules.complete(completion(null, null, "31st", "maybe", "perhaps"));

        assertThat(checked.problems()).containsOnlyKeys("effectiveOn", "serverUpdated", "masterListUpdated");
        assertThat(checked.problems().get("serverUpdated").get(0))
                .isEqualTo("The answer about the server is Yes or No.");
    }

    @Test
    void theBottomBoxesAreBoundedToo() {
        Checked<Completed> checked = RegisterRules.complete(completion("x".repeat(RegisterRules.DEPARTMENT + 1),
                "x".repeat(RegisterRules.APPROVED_BY + 1), null, null, null));

        assertThat(checked.problems()).containsOnlyKeys("department", "approvedBy");
    }

    @Test
    void theRequestIsCalledByTheFirstLineOfWhatIsAsked() {
        assertThat(RegisterRules.titleOf("First line\nsecond line")).isEqualTo("First line");
        assertThat(RegisterRules.titleOf("  one line only  ")).isEqualTo("one line only");
        String title = RegisterRules.titleOf("y".repeat(300));
        assertThat(title).hasSize(120).endsWith("…");
    }

    @Test
    void aDayIsShownYearMonthDay() {
        assertThat(RegisterRules.show(LocalDate.parse("2026-01-05"))).isEqualTo("2026-01-05");
        assertThat(RegisterRules.show(null)).isNull();
    }
}
