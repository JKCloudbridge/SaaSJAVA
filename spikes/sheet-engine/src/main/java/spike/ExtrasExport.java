package spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * POC 2, WP4. Writes POSITIONS only (no text) of what the extras reader found, next to the neutral fixture of a workbook, so that the
 * browser pages can draw a check box, a Yes/No pair and the place of an embedded file where the real form has them. The output goes
 * to a folder outside the repository, like the fixture. Usage: ExtrasExport <workbook> <out.json>
 */
public final class ExtrasExport {

    private ExtrasExport() {
    }

    public static void main(String[] args) throws Exception {
        Extras.Report r = Extras.read(Files.readAllBytes(Path.of(args[0])), Extras.Limits.standard());
        StringBuilder sb = new StringBuilder("{\"boxes\":[");
        boolean first = true;
        for (Extras.Box b : r.boxes) {
            sb.append(first ? "" : ",").append("{\"r\":").append(b.row()).append(",\"c\":").append(b.col()).append(",\"ticked\":")
                    .append(b.checked()).append(",\"cap\":\"").append(b.caption().name().toLowerCase()).append("\"}");
            first = false;
        }
        sb.append("],\"pairs\":[");
        first = true;
        for (Extras.Pair p : r.pairs) {
            boolean yesTicked = false;
            boolean noTicked = false;
            for (Extras.Box b : r.boxes) {
                if (b.row() == p.yesRow() && b.col() == p.yesCol() && b.caption() == Extras.Caption.YES) {
                    yesTicked = b.checked();
                }
                if (b.row() == p.noRow() && b.col() == p.noCol() && b.caption() == Extras.Caption.NO) {
                    noTicked = b.checked();
                }
            }
            sb.append(first ? "" : ",").append("{\"r\":").append(p.yesRow()).append(",\"c\":").append(p.yesCol()).append(",\"yes\":")
                    .append(yesTicked).append(",\"no\":").append(noTicked).append('}');
            first = false;
        }
        sb.append("],\"embedded\":[");
        first = true;
        for (int[] e : r.embeddedAt) {
            sb.append(first ? "" : ",").append("{\"r\":").append(e[0]).append(",\"c\":").append(e[1]).append('}');
            first = false;
        }
        sb.append("]}");
        Files.writeString(Path.of(args[1]), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("positions written: " + r.boxes.size() + " boxes, " + r.pairs.size() + " pairs, " + r.embeddedAt.size() + " embedded");
    }
}
