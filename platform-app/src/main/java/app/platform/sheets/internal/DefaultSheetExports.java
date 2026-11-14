package app.platform.sheets.internal;

import app.platform.sheets.SheetExports;
import app.platform.sheets.internal.WorkbookWriter.Page;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as a file (FA7, ADR-0201; SH3, ADR-0253). The version comes from {@link SheetVersions#readable}, the one
 * rule of who may read a version; the document is read into the typed model and written by {@link WorkbookWriter}.
 *
 * <p>A sheet in format version 1 is written as it always was. A sheet in format version 2 is written with everything
 * it states: the look, the frozen pane, the conditional colours, the page set-up and its formulas, as formulas, each
 * with the answer the server calculates for it stored beside it. The information of the file says only that the
 * platform made it and which Revision it is; nothing a person typed.
 */
@Service
@Transactional(readOnly = true)
class DefaultSheetExports implements SheetExports {

    /** What the information of a file says it was made by. */
    static final String MADE_BY = "Made by the platform.";

    private final SheetVersions versions;
    private final FormulaProperties formulas;
    private final WorkbookWriter writer;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetExports(SheetVersions versions, FormulaProperties formulas, WorkbookWriter writer) {
        this.versions = versions;
        this.formulas = formulas;
        this.writer = writer;
    }

    @Override
    public byte[] xlsx(String ownerObject, UUID ownerId, int versionNo, String footerRight) {
        SheetVersionStore.WithGrid found = versions.readable(ownerObject, ownerId, versionNo);
        int revision = found.row().revisionNo() != null ? found.row().revisionNo() : Math.max(0, versionNo - 1);
        return writer.write(pagesOf(json.readTree(found.grid()), footerRight, formulas.limits()),
                description(revision)).bytes();
    }

    /** The worksheets of a stored document, each with the answers of its formulas. */
    static List<Page> pagesOf(JsonNode stored, String footerRight, FormulaLimits limits) {
        if (!SheetFormats.isFormatTwo(stored)) {
            return List.of(Page.one(SheetGridReader.read(stored), footerRight));
        }
        List<Page> pages = new ArrayList<>();
        for (SheetDocument.Tab tab : SheetDocumentReader.read(stored).sheets()) {
            SheetCalculator.Outcome answers = tab.formulas().isEmpty() && tab.columnFormulas().isEmpty() ? null
                    : SheetCalculator.calculate(tab, tab.body().dimension() == 1
                            ? SheetCalculator.Inputs.emptyRows(WorkbookWriter.SAMPLE_ROWS)
                            : SheetCalculator.Inputs.none(), limits);
            pages.add(new Page(tab.title(), tab.body(), tab, answers, footerRight, false));
        }
        return pages;
    }

    /** The words of the file's information: fixed, and the Revision as people read it. */
    static String description(int revision) {
        return MADE_BY + String.format(Locale.ROOT, " Revision %02d.", Math.max(0, revision));
    }
}
