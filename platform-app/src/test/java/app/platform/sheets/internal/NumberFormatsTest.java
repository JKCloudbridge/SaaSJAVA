package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Number formats and font families of a workbook become keys of the closed lists (SH1B, ADR-0251, ADR-0253): the text
 * of a format or of a family comes from a file, is compared with fixed patterns, and is never stored. No database.
 */
class NumberFormatsTest {

    @ParameterizedTest(name = "{1} is {2}")
    @CsvSource(delimiter = '|', value = {
        "1|0|WHOLE", "2|0.00|TWO_DECIMALS", "3|#,##0|THOUSANDS", "4|#,##0.00|THOUSANDS_TWO_DECIMALS",
        "9|0%|PERCENT", "10|0.00%|PERCENT_TWO_DECIMALS", "164|#,##0_);(#,##0)|THOUSANDS",
        "165|#,##0.00_);[Red](#,##0.00)|THOUSANDS_TWO_DECIMALS", "166|mmm-yy|MONTH_YEAR",
        "14|m/d/yy|SHORT_DATE", "167|dd/mm/yyyy|SHORT_DATE"})
    void aKnownFormatBecomesItsKey(int id, String code, String key) {
        assertThat(NumberFormats.of(id, code).key()).isEqualTo(key);
    }

    @Test
    void theGeneralAndTextFormatsAreNotLossesAndHaveNoKey() {
        assertThat(NumberFormats.of(0, "General")).isEqualTo(NumberFormats.Meaning.GENERAL);
        assertThat(NumberFormats.of(49, "@")).isEqualTo(NumberFormats.Meaning.GENERAL);
        assertThat(NumberFormats.of(164, "General")).isEqualTo(NumberFormats.Meaning.GENERAL);
    }

    @Test
    void anAccountingFormatIsAccounting() {
        assertThat(NumberFormats.of(44, "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)").key())
                .isEqualTo("ACCOUNTING");
        assertThat(NumberFormats.of(175, "_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)").key()).isEqualTo("ACCOUNTING");
    }

    @Test
    void aFormatTheListDoesNotHoldIsReportedAsUnknownAndNotMistakenForAClose() {
        for (String code : new String[] {"0.0\" kg\"", "\"$\"#,##0.00", "[$EUR-407] #,##0.00", "0.000", "#,##0.0"}) {
            NumberFormats.Meaning meaning = NumberFormats.of(168, code);

            assertThat(meaning.unknown()).as(code).isTrue();
            assertThat(meaning.key()).isNull();
        }
    }

    @Test
    void aDateOrTimeFormatIsADateWhateverItsKey() {
        assertThat(NumberFormats.of(22, "m/d/yy h:mm").date()).isTrue();
        assertThat(NumberFormats.of(173, "hh:mm").date()).isTrue();
        assertThat(NumberFormats.of(173, "hh:mm").unknown()).as("a time is text here, not a loss").isFalse();
        assertThat(NumberFormats.of(174, "0.00\" days\"").date()).isFalse();
        assertThat(NumberFormats.of(27, null).date()).as("a built-in date format with no code").isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Arial|SANS_NEUTRAL|EXACT", "Arial Black|DISPLAY_HEAVY|EXACT", "Arial Unicode MS|SANS_UNICODE|EXACT",
        "Calibri Light|SANS_HUMANIST|EXACT", "Segoe UI|SANS_UI|EXACT", "Segoe Print|SANS_HUMANIST|ALIAS",
        "Tahoma|SANS_WIDE|ALIAS",
        "Times New Roman|SERIF_TEXT|EXACT", "Courier New|MONO_CLASSIC|EXACT", "Lucida Console|MONO_CODE|ALIAS",
        "Zorbian Script|SANS|FALLBACK", "Some Mono Face|MONO|FALLBACK", "Old Roman Face|SERIF|FALLBACK",
        "Sans Roman|SANS|FALLBACK"})
    void aFamilyIsMappedByTheTableOrTakesAGenericKey(String family, String key, FontKeys.How how) {
        FontKeys.Mapped mapped = FontKeys.of(family);

        assertThat(mapped.key()).isEqualTo(key);
        assertThat(mapped.how()).isEqualTo(how);
        assertThat(SheetLook.FONTS).contains(mapped.key());
    }

    @Test
    void aMissingFamilyNeverReachesTheLookAsAName() {
        assertThat(FontKeys.of(null).key()).isEqualTo("SANS");
        assertThat(FontKeys.of("<script>x</script>").key()).isIn(SheetLook.FONTS);
    }
}
