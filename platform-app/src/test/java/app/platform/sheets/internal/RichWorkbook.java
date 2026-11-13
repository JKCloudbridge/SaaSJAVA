package app.platform.sheets.internal;

import java.nio.charset.StandardCharsets;

/**
 * Makes a small workbook with a look, for the tests of the reader of format version 2 (SH1B): fonts with names, a
 * theme, solid fills (one with a tint), borders of several styles, number formats, a frozen pane, a conditional
 * colour and a page set-up. Built by hand so that a test states exactly what the file holds. The cell styles are:
 * 0 plain, 1 bold 14 points in the first accent colour of the theme in the Arial family, 2 italic underlined 9 points
 * in a family the table does not know, centred, wrapped, in the middle, with four different borders, 3 a yellow fill
 * and a thin box, 4 a fill from the theme with a tint and a number format the sheet does not have, 5 percent,
 * 6 percent with two decimals in a monospaced family with a text colour of plain black.
 */
final class RichWorkbook {

    static final int PLAIN = 0;
    static final int HEADING = 1;
    static final int UNKNOWN_FONT = 2;
    static final int YELLOW_BOX = 3;
    static final int TINTED = 4;
    static final int PERCENT = 5;
    static final int MONO_PERCENT = 6;

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final String STYLES = "<styleSheet xmlns=\"" + MAIN + "\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0&quot; kg&quot;\"/></numFmts>"
            + "<fonts count=\"4\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"14\"/><color theme=\"4\"/><name val=\"Arial\"/></font>"
            + "<font><i/><u/><sz val=\"9\"/><color rgb=\"FFC00000\"/><name val=\"Zorbian Script\"/></font>"
            + "<font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Courier New\"/></font></fonts>"
            + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/><bgColor indexed=\"64\"/>"
            + "</patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor theme=\"4\" tint=\"0.59999389629810485\"/>"
            + "<bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"3\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>"
            + "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom>"
            + "<diagonal/></border>"
            + "<border><left style=\"hair\"/><right style=\"mediumDashed\"/><top style=\"double\"/><bottom/>"
            + "<diagonal/></border></borders>"
            + "<cellXfs count=\"7\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"2\"><alignment horizontal=\"center\" "
            + "vertical=\"center\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"2\" borderId=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"3\" borderId=\"0\"/>"
            + "<xf numFmtId=\"9\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
            + "<xf numFmtId=\"10\" fontId=\"3\" fillId=\"0\" borderId=\"0\"/></cellXfs>"
            + "<dxfs count=\"2\"><dxf><font><color rgb=\"FF9C0006\"/></font><fill><patternFill>"
            + "<bgColor rgb=\"FFFFC7CE\"/></patternFill></fill></dxf>"
            + "<dxf><font><color rgb=\"FF006100\"/></font></dxf></dxfs></styleSheet>";

    /** A theme whose first accent colour is 4472C4. */
    static final String THEME = "<a:theme xmlns:a=\"" + DRAWING + "\" name=\"t\"><a:themeElements>"
            + "<a:clrScheme name=\"c\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
            + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"44546A\"/></a:dk2>"
            + "<a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2><a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>"
            + "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>"
            + "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>"
            + "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>"
            + "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>"
            + "<a:fontScheme name=\"f\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
            + "<a:fmtScheme name=\"m\"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/>"
            + "</a:fmtScheme></a:themeElements></a:theme>";

    private RichWorkbook() {
    }

    /** A file with the rich styles and the theme; the caller adds rows. */
    static XlsxFile file() {
        return new XlsxFile().styles(STYLES).part("xl/theme/theme1.xml", THEME.getBytes(StandardCharsets.UTF_8));
    }
}
