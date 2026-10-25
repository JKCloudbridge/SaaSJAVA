package app.platform.sheets.internal;

import app.platform.sheets.SheetExports;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as a file (FA7, ADR-0201). The version comes from {@link SheetVersions#readable}, the one rule of who may
 * read a version; the grid is read into the typed model and written by {@link XlsxWriter}. A writer is made for each
 * call because it keeps the styles of the workbook it is writing.
 */
@Service
@Transactional(readOnly = true)
class DefaultSheetExports implements SheetExports {

    private final SheetVersions versions;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetExports(SheetVersions versions) {
        this.versions = versions;
    }

    @Override
    public byte[] xlsx(String ownerObject, UUID ownerId, int versionNo, String footerRight) {
        SheetVersionStore.WithGrid found = versions.readable(ownerObject, ownerId, versionNo);
        SheetGrid grid = SheetGridReader.read(json.readTree(found.grid()));
        return new XlsxWriter().write(grid, footerRight);
    }
}
