package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.Revisions;
import org.junit.jupiter.api.Test;

/**
 * The revision as people read it, the footer text and the file name (FA7, ADR-0201; FA8, ADR-0202), with no database.
 * The
 * same cases are checked on the browser's side ({@code revision.test.ts}) so the screen and the file always agree.
 * Since FA8
 * the footer and the file name take the revision number itself (the database numbers a revision when it first becomes
 * the
 * one in use), and a version stored before that is read as its stored number minus one.
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
    void aRevisionNumberIsWrittenWithTwoDigits() {
        assertThat(TemplateRevisions.show(0)).isEqualTo("00");
        assertThat(TemplateRevisions.show(1)).isEqualTo("01");
        assertThat(TemplateRevisions.show(12)).isEqualTo("12");
        assertThat(TemplateRevisions.show(100)).as("more than two digits only when it must").isEqualTo("100");
        assertThat(TemplateRevisions.show(-3)).as("never negative").isEqualTo("00");
    }

    @Test
    void theFooterIsTheNumberTwoSpacesAndTheRevisionInSingleQuotes() {
        assertThat(TemplateSheetTexts.footer("F/MR/16", 1)).isEqualTo("F/MR/16  Rev No.'01'");
        assertThat(TemplateSheetTexts.footer("  F/MR/16 ", 0)).isEqualTo("F/MR/16  Rev No.'00'");
    }

    @Test
    void theFooterIsBlankUntilTheTemplateHasANumber() {
        assertThat(TemplateSheetTexts.footer(null, 0)).isEmpty();
        assertThat(TemplateSheetTexts.footer("", 0)).isEmpty();
        assertThat(TemplateSheetTexts.footer("   ", 2)).isEmpty();
    }

    @Test
    void theFileNameIsTheNumberWithHyphensAndTheRevision() {
        assertThat(TemplateSheetTexts.fileName("F/MR/16", 0)).isEqualTo("F-MR-16_Rev00.xlsx");
        assertThat(TemplateSheetTexts.fileName("CP/PRD/A-12", 2)).isEqualTo("CP-PRD-A-12_Rev02.xlsx");
        assertThat(TemplateSheetTexts.fileName(null, 0)).isEqualTo("Template_Rev00.xlsx");
    }

    @Test
    void aNumberCanNeverMakeAPathOrAHeaderBreakInTheFileName() {
        String name = TemplateSheetTexts.fileName("../../x\r\nSet-Cookie: a=b/\"q\"", 0);

        assertThat(name).doesNotContain("/").doesNotContain("\\").doesNotContain("\r").doesNotContain("\n")
                .doesNotContain("\"").doesNotContain(" ").endsWith("_Rev00.xlsx");
    }

    @Test
    void theRevisionOfATemplateIsTheSavedOneOrTheStoredNumberMinusOne() {
        assertThat(Templates.revisionShown(java.util.Map.of("revision", "03"), 9)).as("the saved one wins")
                .isEqualTo("03");
        assertThat(Templates.revisionShown(java.util.Map.of(), 2)).as("published before it was saved")
                .isEqualTo("01");
        assertThat(Templates.revisionShown(java.util.Map.of("revision", " "), 1)).isEqualTo("00");
        assertThat(Templates.revisionShown(java.util.Map.of(), 0)).as("never published").isNull();
    }

    @Test
    void theMomentOfTheLastRevisionIsReadFromTheRecordOrIsNothing() {
        assertThat(Templates.lastRevisedAt(java.util.Map.of("lastRevisedAt", "2026-10-25T10:15:30Z")))
                .isEqualTo(java.time.Instant.parse("2026-10-25T10:15:30Z"));
        assertThat(Templates.lastRevisedAt(java.util.Map.of("lastRevisedAt", "not a time"))).isNull();
        assertThat(Templates.lastRevisedAt(java.util.Map.of())).isNull();
    }

    /** The cases the browser reads too (MC1, ADR-0257): the footer with the day, in the zone of each case. */
    @Test
    void theFooterWithTheDayIsWrittenAsTheSharedCasesSay() throws java.io.IOException {
        int checked = 0;
        try (java.io.InputStream in = getClass().getResourceAsStream("/inspection/footer-cases.txt")) {
            assertThat(in).isNotNull();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] part = line.split("\\|", -1);
                String number = part[0].strip();
                int revision = Integer.parseInt(part[1].strip());
                java.time.LocalDate day = part[2].isBlank() ? null : java.time.Instant.parse(part[2].strip())
                        .atZone(java.time.ZoneId.of(part[3].strip())).toLocalDate();
                String expected = part[4].strip().replace("\\n", "\n");
                assertThat(TemplateSheetTexts.footer(number, revision, day)).as(line).isEqualTo(expected);
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(8);
    }

    @Test
    void withoutADayTheFooterIsTheFirstLineAlone() {
        assertThat(TemplateSheetTexts.footer("F/MR/16", 1, null)).isEqualTo(TemplateSheetTexts.footer("F/MR/16", 1));
    }
}
