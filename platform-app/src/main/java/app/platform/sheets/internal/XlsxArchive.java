package app.platform.sheets.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The first guard of a workbook, ours and not the library's (SH1B, ADR-0253, ADR-0195): the archive is read once, in
 * memory, and everything that can be said about it is said here before any library sees a byte.
 *
 * <p>What is refused: more parts than allowed; a part name that appears twice (also when the two differ only by case,
 * the class of flaw in which a library and a reader pick different parts of one file); a part name with {@code ..},
 * a back slash, a leading slash or a control character; macros (a VBA part or a macro content type); and a part, or
 * the whole, that inflates past its limit. The sizes are counted <em>while inflating</em>, for the parts that are
 * skipped as well as the ones that are read, so a zip bomb is stopped at the limit and never inflated in full.
 *
 * <p>A part name is only compared with fixed names and prefixes: it is never used as a path, and nothing is written
 * anywhere. Parts the reader needs are kept (the XML of the workbook, styles, strings, worksheets and drawings, up to
 * a bound); for a picture only its first bytes and size are kept, enough to say what kind it is and never to decode
 * it. Nothing of the file is put in a message.
 */
final class XlsxArchive {

    private static final int HEAD = 64;
    private static final String SHEETS = "xl/worksheets/";
    private static final String DRAWINGS = "xl/drawings/";
    private static final String CONTROLS = "xl/ctrlProps/";

    /** A picture as the archive knows it: its kind from its first bytes and its size, never its content. */
    record Picture(String kind, int bytes) {
    }

    /** What the scan found. */
    static final class Parts {

        private final Map<String, byte[]> xml = new HashMap<>();
        private final Map<String, Picture> pictures = new HashMap<>();
        private int charts;
        private int externalLinks;
        private int embeddings;
        private int pivots;
        private int comments;

        /** The kept part with this name, or null when there is none or it was not kept. */
        byte[] part(String name) {
            return xml.get(name);
        }

        /** The kind and size of a picture part, or null when the name is not one. */
        Picture picture(String name) {
            return pictures.get(name);
        }

        /** How many pictures in each kind, in the order raster then vector: png, jpeg, other, emf, wmf. */
        int pictures(boolean vector) {
            int n = 0;
            for (Picture picture : pictures.values()) {
                boolean isVector = "emf".equals(picture.kind()) || "wmf".equals(picture.kind());
                if (isVector == vector) {
                    n++;
                }
            }
            return n;
        }

        int charts() {
            return charts;
        }

        int externalLinks() {
            return externalLinks;
        }

        int embeddings() {
            return embeddings;
        }

        int pivots() {
            return pivots;
        }

        int comments() {
            return comments;
        }
    }

    private XlsxArchive() {
    }

    /**
     * Scans the archive.
     *
     * @throws ImportRefusal when the bytes are not a workbook, are over a limit, or carry what is never accepted
     */
    static Parts scan(byte[] file, ImportProperties settings) {
        if (file.length >= 4 && (file[0] & 0xff) == 0xd0 && (file[1] & 0xff) == 0xcf && (file[2] & 0xff) == 0x11
                && (file[3] & 0xff) == 0xe0) {
            throw new ImportRefusal("old_format", "This is an older spreadsheet format or a file protected by a "
                    + "password. Save it as .xlsx without a password and upload that.");
        }
        if (file.length < 4 || file[0] != 'P' || file[1] != 'K' || file[2] != 3 || file[3] != 4) {
            throw ImportRefusal.notXlsx();
        }
        Parts parts = new Parts();
        Set<String> names = new HashSet<>();
        long inflated = 0;
        long retained = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                entries++;
                if (entries > settings.maxEntries()) {
                    throw ImportRefusal.tooLarge("entries", "The file has more than " + settings.maxEntries()
                            + " parts, which is more than a workbook is allowed to have.");
                }
                String name = entry.getName();
                if (unsafeName(name)) {
                    throw new ImportRefusal("unsafe_part_name", "The file has a part whose name is not allowed in a "
                            + "workbook.");
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new ImportRefusal("duplicate_part", "The file has two parts with the same name, which a "
                            + "workbook never has.");
                }
                if (name.endsWith("vbaProject.bin")) {
                    throw ImportRefusal.macros();
                }
                boolean directory = entry.isDirectory();
                boolean keep = !directory && wanted(name);
                boolean picture = !directory && name.startsWith("xl/media/");
                ByteArrayOutputStream body = keep ? new ByteArrayOutputStream() : null;
                byte[] head = new byte[HEAD];
                int headLength = 0;
                long partSize = 0;
                int n = zip.read(buffer);
                while (n >= 0) {
                    if (n > 0) {
                        if (picture && headLength < HEAD) {
                            int copy = Math.min(n, HEAD - headLength);
                            System.arraycopy(buffer, 0, head, headLength, copy);
                            headLength += copy;
                        }
                        partSize += n;
                        inflated += n;
                        if (partSize > settings.maxPartBytes() || inflated > settings.maxInflatedBytes()) {
                            throw ImportRefusal.unpackedTooLarge();
                        }
                        if (body != null) {
                            body.write(buffer, 0, n);
                        }
                    }
                    n = zip.read(buffer);
                }
                if (body != null) {
                    retained += partSize;
                    if (retained > 2L * settings.maxPartBytes()) {
                        throw ImportRefusal.unpackedTooLarge();
                    }
                    parts.xml.put(name, body.toByteArray());
                    if ("[Content_Types].xml".equals(name) && macroTypes(parts.xml.get(name))) {
                        throw ImportRefusal.macros();
                    }
                }
                if (picture) {
                    parts.pictures.put(name, new Picture(kindOf(Arrays.copyOf(head, headLength)), (int) partSize));
                }
                count(parts, name, directory);
                entry = zip.getNextEntry();
            }
        } catch (IOException | IllegalArgumentException e) {
            // The library's message is not repeated: it can quote the name of a part.
            throw ImportRefusal.notXlsx();
        }
        return parts;
    }

    private static boolean unsafeName(String name) {
        if (name.isEmpty() || name.contains("..") || name.indexOf('\\') >= 0 || name.charAt(0) == '/') {
            return true;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < ' ' || name.charAt(i) == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean macroTypes(byte[] contentTypes) {
        String text = new String(contentTypes, StandardCharsets.ISO_8859_1);
        return text.contains("macroEnabled") || text.contains("vbaProject");
    }

    private static boolean wanted(String name) {
        if ("[Content_Types].xml".equals(name) || "xl/workbook.xml".equals(name)
                || "xl/_rels/workbook.xml.rels".equals(name) || "xl/sharedStrings.xml".equals(name)
                || "xl/styles.xml".equals(name) || "xl/theme/theme1.xml".equals(name)) {
            return true;
        }
        boolean text = name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".vml");
        return text && (name.startsWith(SHEETS) || name.startsWith(DRAWINGS) || name.startsWith(CONTROLS));
    }

    private static void count(Parts parts, String name, boolean directory) {
        if (directory) {
            return;
        }
        if (name.startsWith("xl/charts/chart") && name.endsWith(".xml")) {
            parts.charts++;
        } else if (name.startsWith("xl/externalLinks/externalLink") && name.endsWith(".xml")) {
            parts.externalLinks++;
        } else if (name.startsWith("xl/embeddings/")) {
            parts.embeddings++;
        } else if (name.startsWith("xl/pivotTables/pivotTable") && name.endsWith(".xml")) {
            parts.pivots++;
        } else if (name.startsWith("xl/comments") && name.endsWith(".xml")
                || name.startsWith("xl/threadedComments/") && name.endsWith(".xml")) {
            parts.comments++;
        }
    }

    /** The kind of a picture from its first bytes. A vector picture is typed here and never decoded anywhere. */
    static String kindOf(byte[] head) {
        if (head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "png";
        }
        if (head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        if (head.length >= 44 && head[0] == 1 && head[1] == 0 && head[2] == 0 && head[3] == 0 && head[40] == ' '
                && head[41] == 'E' && head[42] == 'M' && head[43] == 'F') {
            return "emf";
        }
        if (head.length >= 4 && (head[0] & 0xff) == 0xd7 && (head[1] & 0xff) == 0xcd) {
            return "wmf";
        }
        return "other";
    }
}
