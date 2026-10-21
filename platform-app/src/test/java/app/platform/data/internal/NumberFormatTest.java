package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The closed language numbers are written in (FA2A, ADR-0176, ADR-0184): what it accepts, what it writes, and a corpus
 * of
 * hostile formats, each refused with a message that says which part is wrong and never repeats the text.
 */
class NumberFormatTest {

    private static final Map<String, Object> RECORD = Map.of("category", "WI", "subCategory", "PRD");

    @Test
    void aFormatIsWrittenWithTheValuesOfTheRecordAndACounterPaddedWithZeros() {
        NumberFormat format = NumberFormat.parse("WI/{field:subCategory}/{seq:2}");

        assertThat(format.render(RECORD, 3)).isEqualTo("WI/PRD/03");
        assertThat(format.render(RECORD, 12)).isEqualTo("WI/PRD/12");
        assertThat(format.fields()).containsExactly("subCategory");
    }

    @Test
    void aCounterLongerThanItsWidthIsNotCut() {
        NumberFormat format = NumberFormat.parse("QSP-{seq:2}");

        assertThat(format.render(Map.of(), 123)).isEqualTo("QSP-123");
        assertThat(format.render(Map.of(), 1_234_567_890_123L)).isEqualTo("QSP-1234567890123");
    }

    @Test
    void thePrefixIsEverythingInFrontOfTheCounter() {
        assertThat(NumberFormat.parse("F/EHS/{field:subCategory}/{seq:2}").prefix(RECORD)).isEqualTo("F/EHS/PRD/");
        assertThat(NumberFormat.parse("IMS/{seq:2}").prefix(Map.of())).isEqualTo("IMS/");
    }

    @Test
    void theCounterMayComeBeforeAnotherPart() {
        NumberFormat format = NumberFormat.parse("{seq:3}-{field:category}");

        assertThat(format.render(RECORD, 7)).isEqualTo("007-WI");
        assertThat(format.prefix(RECORD)).isEmpty();
    }

    @Test
    void theLongestNumberCoversTheLongestCodeAndACounterThatGrows() {
        assertThat(NumberFormat.parse("WI/{field:subCategory}/{seq:2}").longestNumber()).isEqualTo(3 + 10 + 1 + 12);
    }

    @Test
    void aMissingOrEmptyPartRefusesTheNumber() {
        NumberFormat format = NumberFormat.parse("WI/{field:subCategory}/{seq:2}");

        assertThatThrownBy(() -> format.render(Map.of(), 1)).isInstanceOfSatisfying(NumberSeriesException.class,
                refusal -> assertThat(refusal.reason()).isEqualTo(Reason.PART_MISSING));
        assertThatThrownBy(() -> format.render(Map.of("subCategory", ""), 1))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.PART_MISSING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "a/b/c/d/e/f", "toolongvalue", "x;y", "<b>", "{x}", "aé", "a\nb", "$1",
        "../up", "a.b"})
    void aPartThatIsNotAShortCodeRefusesTheNumberWithoutRepeatingIt(String value) {
        NumberFormat format = NumberFormat.parse("WI/{field:subCategory}/{seq:2}");

        assertThatThrownBy(() -> format.render(Map.of("subCategory", value), 1))
                .isInstanceOfSatisfying(NumberSeriesException.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(Reason.PART_NOT_A_CODE);
                    assertThat(refusal.getMessage()).doesNotContain(value.trim()).isEqualTo(
                            "A part of the number is not a short code.");
                });
    }

    @Test
    void aValueThatIsNotTextIsNotACode() {
        NumberFormat format = NumberFormat.parse("WI/{field:subCategory}/{seq:2}");

        assertThatThrownBy(() -> format.render(Map.of("subCategory", 42), 1))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.PART_NOT_A_CODE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                                         // nothing
        "WI/",                                      // no counter
        "{seq:2}{seq:2}",                           // two counters
        "{seq:0}", "{seq:10}", "{seq:x}", "{seq:}", // a counter of the wrong width
        "{field:}", "{field:a b}", "{field:a.b}", "{field:1a}", "{field:a-b}", // a field name that is not one
        "{clock:now}", "{now}", "{}", "{ seq:2}",   // not a part of the language
        "<script>{seq:2}", "<b>{seq:2}</b>",        // markup
        "${x}{seq:2}", "#{x}{seq:2}",               // a template placeholder of another language
        "{seq:2",                                   // not closed
        "}{seq:2}", "{seq:2}}",                     // a stray brace
        "{{seq:2}}", "{field:{seq:2}}",             // nested
        "A B{seq:2}", "A.B{seq:2}", "Aé{seq:2}", "A\n{seq:2}",   // characters that are not allowed
        "{field:a}{field:b}{field:c}{field:d}{seq:2}",                 // more than four parts in braces
        "ABCDEFGHIJK{seq:2}",                                           // more than ten fixed characters
        "AB/CD/EF/{seq:2}/GH",                                          // more than ten fixed characters in all
    })
    void aFormatOutsideTheLanguageIsRefusedWithoutRepeatingIt(String format) {
        assertThatThrownBy(() -> NumberFormat.parse(format)).isInstanceOf(IllegalArgumentException.class)
                .satisfies(refusal -> {
                    String message = refusal.getMessage();
                    assertThat(message).isNotBlank();
                    if (format.length() > 3) {
                        assertThat(message).doesNotContain(format);
                    }
                });
    }

    @Test
    void aFormatOfTheFirstCustomerIsInTheLanguage() {
        List<String> formats = List.of("IMS/{seq:2}", "QSP-{seq:2}", "CORP-QSP-{seq:2}", "ESSP-{seq:2}",
                "WI/{field:subCategory}/{seq:2}", "F/EHS/{field:subCategory}/{seq:2}",
                "L/EHS/{field:subCategory}/{seq:2}", "CSF/{field:subCategory}/{seq:2}");

        formats.forEach(format -> assertThat(NumberFormat.parse(format).parts()).isNotEmpty());
    }
}
