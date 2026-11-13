package app.platform.sheets.internal;

import app.platform.sheets.SheetExports;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as a file (FA7, ADR-0201). The version comes from {@link SheetVersions#readable}, the one rule of who may
 * read a version; the grid is read into the typed model and written by {@link XlsxWriter}. A writer is made for each
 * call because it keeps the styles of the workbook it is writing.
 */
@Service
@Transactional(readOnly = true)
class DefaultSheetExports implements SheetExports {

    /** What the information of a file written from a sheet in format version 2 says (plain words, no markup). */
    static final String LOOK_NOT_YET = "Made from a sheet in the new format. Its colours, fonts, borders and number "
            + "formats are not in this file yet.";

    private final SheetVersions versions;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetExports(SheetVersions versions) {
        this.versions = versions;
    }

    @Override
    public byte[] xlsx(String ownerObject, UUID ownerId, int versionNo, String footerRight) {
        SheetVersionStore.WithGrid found = versions.readable(ownerObject, ownerId, versionNo);
        JsonNode stored = json.readTree(found.grid());
        SheetGrid grid = SheetGridReader.read(stored);
        // A sheet in format version 2 is written as far as format version 1 goes: its text, widths, heights, merges and
        // the footer. Its look is drawn by a later release (SH3), and the file says so.
        String note = SheetFormats.isFormatTwo(stored) ? LOOK_NOT_YET : null;
        return new XlsxWriter().write(grid, footerRight, false, note);
    }
}
