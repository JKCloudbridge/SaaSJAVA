package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The rules for the text of a change request (FA5, ADR-0191), with no database: plain text is accepted; empty, too
 * long, markup, control characters and direction marks are refused; and a refusal says what is expected without
 * repeating what was typed.
 */
class ChangeTextTest {

    @Test
    void plainTextIsAcceptedWithItsLinksAndPunctuation() {
        assertThat(ChangeText.problem("comment", "Torque 25 Nm ± 2, see http://example.test/a?b=1&c=2.", 200,
                true)).isEmpty();
        assertThat(ChangeText.problem("title", "Row 3: the limit is 5 < 6 only in words", 200, false))
                .as("a less-than sign is markup").isPresent();
    }

    @Test
    void emptyAndBlankTextIsRefused() {
        assertThat(ChangeText.problem("comment", null, 100, true)).contains("Write the comment.");
        assertThat(ChangeText.problem("comment", "   \n ", 100, true)).contains("Write the comment.");
    }

    @Test
    void textLongerThanTheLimitIsRefusedAndTheLimitItselfIsAllowed() {
        assertThat(ChangeText.problem("title", "x".repeat(120), 120, false)).isEmpty();
        assertThat(ChangeText.problem("title", "x".repeat(121), 120, false))
                .contains("The title is at most 120 characters.");
    }

    @Test
    void markupIsRefusedAndTheWordsDoNotRepeatIt() {
        String typed = "<script>alert('secret-marker')</script>";

        String words = ChangeText.problem("comment", typed, 500, true).orElseThrow();

        assertThat(words).doesNotContain("secret-marker").doesNotContain("script");
        assertThat(ChangeText.problem("comment", "a > b", 500, true)).isPresent();
    }

    @Test
    void lineBreaksAreAllowedOnlyWhereLinesAreAndOtherControlCharactersNever() {
        assertThat(ChangeText.problem("comment", "one\ntwo", 100, true)).isEmpty();
        assertThat(ChangeText.problem("comment", "one\r\ntwo", 100, true)).isEmpty();
        assertThat(ChangeText.problem("title", "one\ntwo", 100, false)).contains("The title is one line.");
        assertThat(ChangeText.problem("comment", "bell\u0007", 100, true)).isPresent();
        assertThat(ChangeText.problem("comment", "tab\there", 100, true)).as("a tab is a control character")
                .isPresent();
        assertThat(ChangeText.problem("comment", "nul\u0000", 100, true)).isPresent();
    }

    @Test
    void marksThatChangeTheDirectionOfTextAreRefused() {
        for (char mark : new char[] {'؜', '‎', '‏', '‪', '‮', '⁦', '⁩'}) {
            assertThat(ChangeText.problem("comment", "safe" + mark + "text", 100, true))
                    .as("U+" + Integer.toHexString(mark)).isPresent();
        }
    }

    @Test
    void cleanStripsTheEndsAndUsesOneKindOfLineBreak() {
        assertThat(ChangeText.clean("  one\r\ntwo\rthree \n")).isEqualTo("one\ntwo\nthree");
        assertThat(ChangeText.clean(null)).isEmpty();
    }
}
