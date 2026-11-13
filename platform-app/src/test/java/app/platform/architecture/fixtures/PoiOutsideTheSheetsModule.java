package app.platform.architecture.fixtures;

import org.apache.poi.ss.usermodel.BorderStyle;

/**
 * A class outside the sheets module that names the workbook library, to prove the rule that only the sheets module may
 * do so fails when it should (SH1B, ADR-0253). It is never used by the application.
 */
public final class PoiOutsideTheSheetsModule {

    private PoiOutsideTheSheetsModule() {
    }

    /** Names a type of the library. */
    public static BorderStyle thin() {
        return BorderStyle.THIN;
    }
}
