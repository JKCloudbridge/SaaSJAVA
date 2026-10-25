package app.platform.sheets;

import java.util.UUID;

/**
 * A sheet as a file, for the module that owns what the sheet belongs to (FA7, ADR-0201). The sheets module knows no
 * business word: the module decides <em>whether</em> a file may be given at all (a setting of its own, an ability) and
 * what its footer says; this call decides what is in it and who may see that version.
 *
 * <p><strong>Who may read the version is the rule of the Sheet tab and nothing else</strong> ({@code SheetOwners}): the
 * record must exist and be one the member reads, and a member who may only read sees the versions in use or past. A
 * version the member may not see answers like one that does not exist. Every cell is written as text, never as a
 * formula, with no macro, link or image, and the size is bounded. It is reachable from the cluster only (an
 * architecture test names it). Nothing of the sheet is logged.
 */
public interface SheetExports {

    /**
     * The sheet as an .xlsx workbook: the band of column labels, the header rows, the rows, every cell bordered, and
     * one footer row across the full width. The input cells of a template are written empty.
     *
     * @param ownerObject the owner's object
     * @param ownerId the owner record
     * @param versionNo the number of the version to write
     * @param footerRight the text at the right of the footer row; empty leaves the row blank
     * @return the bytes of the workbook
     * @throws app.platformapi.ApiException {@code NOT_FOUND} (no such version, or not for this member) and
     *         {@code PAYLOAD_TOO_LARGE}
     */
    byte[] xlsx(String ownerObject, UUID ownerId, int versionNo, String footerRight);
}
