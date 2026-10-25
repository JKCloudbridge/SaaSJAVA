package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.Revisions;
import org.junit.jupiter.api.Test;

/**
 * The revision as people read it, the footer text and the file name (FA7, ADR-0201), with no database. The same cases
 * are checked on the browser's side ({@code revision.test.ts}) so the screen and the file always agree.
 */
class TemplateSheetTextsTest {

    @Test
    void theStoredNumberIsShownMinusOneWithTwoDigits() {
        assertThat(Revisions.show(1)).isEqualTo("00");
        assertThat(Revisions.show(2)).isEqualTo("01");
        assertThat(Revisions.show(10)).isEqualTo("09");
        assertThat(Revisions.show(100)).isEqualTo("99");
        assertThat(Revisions.show(101)).as("more than two digits only when it must").isEqualTo("100");
        assertThat(Revisions.show(0)).as("nothing stored is never negative").isEqualTo("00");
        assertThat(Revisions.words(3)).isEqualTo("Revision 02");
    }

    @Test
    void theFooterIsTheNumberTwoSpacesAndTheRevisionInSingleQuotes() {
        assertThat(TemplateSheetTexts.footer("F/MR/16", 2)).isEqualTo("F/MR/16  Rev No.'01'");
        assertThat(TemplateSheetTexts.footer("  F/MR/16 ", 1)).isEqualTo("F/MR/16  Rev No.'00'");
    }

    @Test
    void theFooterIsBlankUntilTheTemplateHasANumber() {
        assertThat(TemplateSheetTexts.footer(null, 1)).isEmpty();
        assertThat(TemplateSheetTexts.footer("", 1)).isEmpty();
        assertThat(TemplateSheetTexts.footer("   ", 3)).isEmpty();
    }

    @Test
    void theFileNameIsTheNumberWithHyphensAndTheRevision() {
        assertThat(TemplateSheetTexts.fileName("F/MR/16", 1)).isEqualTo("F-MR-16_Rev00.xlsx");
        assertThat(TemplateSheetTexts.fileName("CP/PRD/A-12", 3)).isEqualTo("CP-PRD-A-12_Rev02.xlsx");
        assertThat(TemplateSheetTexts.fileName(null, 1)).isEqualTo("Template_Rev00.xlsx");
    }

    @Test
    void aNumberCanNeverMakeAPathOrAHeaderBreakInTheFileName() {
        String name = TemplateSheetTexts.fileName("../../x\r\nSet-Cookie: a=b/\"q\"", 1);

        assertThat(name).doesNotContain("/").doesNotContain("\\").doesNotContain("\r").doesNotContain("\n")
                .doesNotContain("\"").doesNotContain(" ").endsWith("_Rev00.xlsx");
    }
}
