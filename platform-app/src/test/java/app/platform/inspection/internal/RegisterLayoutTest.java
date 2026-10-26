package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.RegisterLayout.Parsed;
import app.platform.inspection.internal.RegisterLayout.Part;
import app.platform.sheets.SheetPages.Cell;
import app.platform.sheets.SheetPages.Found;
import app.platform.sheets.SheetPages.Page;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where every part of the Document Change Register sits on its page and how a filled file is read back (FA8, ADR-0202),
 * with no database: every part to its place and back, the parts a requester types against the parts the platform and
 * the
 * approvers write, and what a file may not hold.
 */
class RegisterLayoutTest {

    private static RegisterValues values() {
        return new RegisterValues("DCN-7", "Quality manual", "QM-01", "02", LocalDate.parse("2026-10-25"), "user-a",
                "Change the title", "Because the plant moved", "All plants", "user-a (Plant QMS), 2026-10-26",
                "Approved electronically on 2026-10-26", "03", LocalDate.parse("2026-10-27"),
                LocalDate.parse("2026-10-26"), "Plant 1 · Production", "Old format F-1",
                LocalDate.parse("2026-11-01"), "YES", "NO");
    }

    private static Cell at(Page page, int row, int column) {
        return page.cells().stream().filter(cell -> cell.row() == row && cell.column() == column).findFirst()
                .orElseThrow(() -> new AssertionError("no cell at " + row + "," + column));
    }

    @Test
    void thePageIsTheFourteenRowsAndTwoColumnsOfTheRegister() {
        Page page = RegisterLayout.page(values());

        assertThat(page.columnWidths()).hasSize(RegisterLayout.COLUMNS);
        assertThat(page.rowHeights()).hasSize(RegisterLayout.ROWS);
        assertThat(at(page, 1, 1).text()).isEqualTo("Document Change Register");
        assertThat(page.merges()).hasSize(6);
    }

    @Test
    void everyPartIsAtItsPlaceWithItsLabelThenItsValue() {
        Page page = RegisterLayout.page(values());

        assertThat(at(page, 2, 1).text()).isEqualTo("Document Name : Quality manual");
        assertThat(at(page, 2, 2).text()).isEqualTo("Request No:- DCN-7");
        assertThat(at(page, 3, 2).text()).isEqualTo("Requested Date:- 2026-10-25");
        assertThat(at(page, 4, 1).text()).isEqualTo("Document No. : QM-01");
        assertThat(at(page, 4, 2).text()).isEqualTo("Rev. No: 02");
        assertThat(at(page, 5, 1).text()).isEqualTo("Change Requested -\nChange the title");
        assertThat(at(page, 6, 1).text()).isEqualTo("Requested by : user-a");
        assertThat(at(page, 7, 1).text()).isEqualTo("Basis for Change :\nBecause the plant moved");
        assertThat(at(page, 8, 1).text()).isEqualTo("Horizontal Deployment (If applicable) :\nAll plants");
        assertThat(at(page, 9, 1).text()).startsWith("Approved By: user-a (Plant QMS)");
        assertThat(at(page, 9, 2).text()).isEqualTo("Signature Approved electronically on 2026-10-26");
        assertThat(at(page, 10, 1).text()).isEqualTo("New Rev. No: 03");
        assertThat(at(page, 10, 2).text()).isEqualTo("Effective Date : 2026-10-27");
        assertThat(at(page, 11, 1).text()).isEqualTo("New Revision date: 2026-10-26");
        assertThat(at(page, 11, 2).text()).isEqualTo("Department: Plant 1 · Production");
        assertThat(at(page, 12, 1).text()).isEqualTo("Format /Document obsoleted (if applicable) Old format F-1");
        assertThat(at(page, 12, 2).text()).isEqualTo("Effective Date : 2026-11-01");
        assertThat(at(page, 13, 2).text()).isEqualTo("Yes,revised document updated on common server");
        assertThat(at(page, 14, 2).text()).isEqualTo("No,master list not updated w.r.t. latest revision");
    }

    @Test
    void aBlankPartShowsItsLabelAloneAndAnUnansweredQuestionIsBlank() {
        RegisterValues blank = new RegisterValues("DCN-1", "Doc", null, "00", LocalDate.parse("2026-10-25"), null,
                "Change", null, null, null, null, null, null, null, null, null, null, null, null);
        Page page = RegisterLayout.page(blank);

        assertThat(at(page, 4, 1).text()).isEqualTo("Document No. :");
        assertThat(at(page, 11, 2).text()).as("no department is not an error").isEqualTo("Department:");
        assertThat(at(page, 13, 2).text()).isEmpty();
        assertThat(at(page, 14, 2).text()).isEmpty();
    }

    private static List<Found> found(Page page) {
        List<Found> cells = new ArrayList<>();
        for (Cell cell : page.cells()) {
            if (!cell.text().isBlank()) {
                cells.add(new Found(cell.row(), cell.column(), cell.text()));
            }
        }
        return cells;
    }

    @Test
    void theTypedPartsComeBackFromAPageThatWasDrawnFromThem() {
        Parsed parsed = RegisterLayout.parse(found(RegisterLayout.page(values())));

        assertThat(parsed.refusal()).isNull();
        assertThat(parsed.typed().values()).containsEntry(Part.REQUEST_NO, "DCN-7")
                .containsEntry(Part.CHANGE_REQUESTED, "Change the title")
                .containsEntry(Part.BASIS_FOR_CHANGE, "Because the plant moved")
                .containsEntry(Part.HORIZONTAL_DEPLOYMENT, "All plants")
                .containsEntry(Part.OBSOLETED_FORMAT, "Old format F-1");
        assertThat(parsed.typed().dateText()).isEqualTo("2026-11-01");
        assertThat(parsed.typed().values()).as("the platform and the approvers write the rest")
                .doesNotContainKeys(Part.DOCUMENT_NAME, Part.REQUESTED_BY, Part.APPROVED_BY, Part.DEPARTMENT,
                        Part.NEW_REV_NO, Part.EFFECTIVE_DATE);
    }

    @Test
    void aValueTypedWithoutItsLabelIsTakenAsItIs() {
        Parsed parsed = RegisterLayout.parse(List.of(new Found(5, 1, "Only the words"),
                new Found(2, 2, "  DCN-9  ")));

        assertThat(parsed.typed().values()).containsEntry(Part.CHANGE_REQUESTED, "Only the words")
                .containsEntry(Part.REQUEST_NO, "DCN-9");
    }

    @Test
    void aLabelSpelledWithOtherSpacesAndCaseIsStillTheLabel() {
        assertThat(RegisterLayout.withoutLabel("request  no:-   DCN-3", "Request No:-")).isEqualTo("DCN-3");
        assertThat(RegisterLayout.withoutLabel("Change Requested -\nIn two\nlines", "Change Requested -"))
                .isEqualTo("In two\nlines");
        assertThat(RegisterLayout.withoutLabel("Basis for Change :", "Basis for Change :")).isEmpty();
    }

    @Test
    void textOutsideTheRegisterIsRefusedInWordsThatNameThePlaceAndNeverTheText() {
        Parsed parsed = RegisterLayout.parse(List.of(new Found(5, 1, "fine"), new Found(15, 1, "SECRET-TEXT")));

        assertThat(parsed.typed()).isNull();
        assertThat(parsed.refusal()).contains("row 15").contains("column A").doesNotContain("SECRET-TEXT");
        assertThat(RegisterLayout.parse(List.of(new Found(3, 3, "SECRET-TEXT"))).refusal()).contains("column C")
                .doesNotContain("SECRET-TEXT");
    }

    @Test
    void aFileWithNoTypedPartGivesNothingAndIsNotARefusal() {
        Parsed parsed = RegisterLayout.parse(List.of(new Found(2, 1, "Document Name : Something")));

        assertThat(parsed.refusal()).isNull();
        assertThat(parsed.typed().values()).isEmpty();
        assertThat(parsed.typed().dateText()).isNull();
    }

    @Test
    void aDayCanBeWrittenInThreeWaysAndNoOtherWay() {
        assertThat(RegisterLayout.parseDate("2026-12-31")).contains(LocalDate.parse("2026-12-31"));
        assertThat(RegisterLayout.parseDate("31-12-2026")).contains(LocalDate.parse("2026-12-31"));
        assertThat(RegisterLayout.parseDate("31/12/2026")).contains(LocalDate.parse("2026-12-31"));
        assertThat(RegisterLayout.parseDate("12/31/2026")).isEmpty();
        assertThat(RegisterLayout.parseDate("next week")).isEmpty();
        assertThat(RegisterLayout.parseDate("  ")).isEmpty();
        assertThat(RegisterLayout.parseDate(null)).isEmpty();
    }

    @Test
    void theTypedPartsAreExactlyTheOnesTheRequesterFills() {
        List<Part> typed = new ArrayList<>();
        for (Part part : Part.values()) {
            if (part.typed()) {
                typed.add(part);
            }
        }

        assertThat(typed).containsExactlyInAnyOrder(Part.REQUEST_NO, Part.CHANGE_REQUESTED, Part.BASIS_FOR_CHANGE,
                Part.HORIZONTAL_DEPLOYMENT, Part.OBSOLETED_FORMAT, Part.OBSOLETED_DATE);
    }
}
