package spike;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The hardened way to open a workbook with POI. Two layers: (1) a pre-scan of the archive in our own code, counted while
 * inflating (entry count, unique names, size of each part and of the whole, macros), and (2) POI's own global limits.
 * Outcomes never carry text of the file.
 */
public final class PoiSafe {

    /** What happened to a file. */
    public enum Outcome { REFUSED_BY_PRESCAN, REFUSED_BY_POI_LIMIT, REFUSED_BY_POI_FORMAT, ACCEPTED, TIMEOUT, OUT_OF_MEMORY,
        STACK_OVERFLOW, OTHER_ERROR }

    /** The result with a stable reason key (a class name or a short code), never a message. */
    public record Result(Outcome outcome, String reason, long millis, int sheets, int externalLinkParts, int hiddenSheets) {
    }

    /** The limits, matching the settings of the current reader (ADR-0195). */
    public record Limits(int maxFileBytes, long maxPartBytes, long maxInflatedBytes, int maxEntries, long maxTextBytes,
            double minInflateRatio, long timeoutMillis) {
        public static Limits standard() {
            return new Limits(5_000_000, 20_000_000L, 100_000_000L, 100, 10_000_000L, 0.01, 5_000);
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "poi-open");
        t.setDaemon(true);
        return t;
    });

    private PoiSafe() {
    }

    /** POI's limits are JVM-wide static settings; set them once, before the first workbook is opened. */
    public static void configurePoi(Limits l) {
        ZipSecureFile.setMinInflateRatio(l.minInflateRatio());
        ZipSecureFile.setMaxEntrySize(l.maxPartBytes());
        ZipSecureFile.setMaxTextSize(l.maxTextBytes());
        ZipSecureFile.setMaxFileCount(l.maxEntries());
        IOUtils.setByteArrayMaxOverride((int) l.maxPartBytes());
    }

    public static Result open(byte[] file, Limits limits, boolean prescan) {
        long start = System.nanoTime();
        if (file.length > limits.maxFileBytes()) {
            return result(Outcome.REFUSED_BY_PRESCAN, "file_too_large", start, 0, 0, 0);
        }
        int externalParts = 0;
        if (prescan) {
            try {
                externalParts = prescan(file, limits);
            } catch (Refused r) {
                return result(Outcome.REFUSED_BY_PRESCAN, r.getMessage(), start, 0, 0, 0);
            } catch (IOException e) {
                return result(Outcome.REFUSED_BY_PRESCAN, "not_a_zip", start, 0, 0, 0);
            }
        }
        final int links = externalParts;
        Future<int[]> job = POOL.submit(() -> {
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(file));
                    XSSFWorkbook wb = new XSSFWorkbook(pkg)) {
                int hidden = 0;
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    if (wb.getSheetVisibility(i) != org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE) {
                        hidden++;
                    }
                }
                int cells = 0;
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    for (org.apache.poi.ss.usermodel.Row row : wb.getSheetAt(i)) {
                        cells += row.getPhysicalNumberOfCells();
                    }
                }
                return new int[] {wb.getNumberOfSheets(), hidden, cells};
            }
        });
        try {
            int[] r = job.get(limits.timeoutMillis(), TimeUnit.MILLISECONDS);
            return result(Outcome.ACCEPTED, "ok", start, r[0], links, r[1]);
        } catch (TimeoutException e) {
            job.cancel(true);
            return result(Outcome.TIMEOUT, "timeout", start, 0, links, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(Outcome.OTHER_ERROR, "interrupted", start, 0, links, 0);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            String name = c.getClass().getSimpleName();
            if (c instanceof OutOfMemoryError) {
                return result(Outcome.OUT_OF_MEMORY, name, start, 0, links, 0);
            }
            if (c instanceof StackOverflowError) {
                return result(Outcome.STACK_OVERFLOW, name, start, 0, links, 0);
            }
            if (c instanceof org.apache.poi.openxml4j.exceptions.InvalidFormatException
                    || c instanceof org.apache.poi.EmptyFileException
                    || c instanceof org.apache.poi.poifs.filesystem.NotOLE2FileException
                    || c instanceof org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
                    || c instanceof org.apache.poi.ooxml.POIXMLException) {
                return result(Outcome.REFUSED_BY_POI_FORMAT, name, start, 0, links, 0);
            }
            if (c instanceof IOException || c instanceof org.apache.poi.util.RecordFormatException
                    || c instanceof IllegalStateException || c instanceof IllegalArgumentException) {
                return result(Outcome.REFUSED_BY_POI_LIMIT, name, start, 0, links, 0);
            }
            return result(Outcome.OTHER_ERROR, name, start, 0, links, 0);
        }
    }

    private static Result result(Outcome o, String reason, long startNanos, int sheets, int links, int hidden) {
        return new Result(o, reason, (System.nanoTime() - startNanos) / 1_000_000, sheets, links, hidden);
    }

    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String key) {
            super(key, null, false, false);
        }
    }

    /**
     * Reads the archive once in our own code, counting while inflating. Returns the number of external-link parts (they are
     * not refused: the real supplier workbook carries five; they are never followed and are counted for the notes).
     */
    static int prescan(byte[] file, Limits l) throws IOException {
        if (file.length >= 4 && (file[0] & 0xff) == 0xd0 && (file[1] & 0xff) == 0xcf) {
            throw new Refused("old_format_or_encrypted");
        }
        Set<String> names = new HashSet<>();
        long total = 0;
        int entries = 0;
        int externalParts = 0;
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries++;
                if (entries > l.maxEntries()) {
                    throw new Refused("entries");
                }
                String name = e.getName();
                if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new Refused("duplicate_part");
                }
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new Refused("unsafe_part_name");
                }
                if (name.equals("xl/vbaProject.bin") || name.endsWith(".bin") && name.startsWith("xl/vba")) {
                    throw new Refused("macros");
                }
                if (name.startsWith("xl/externalLinks/") && name.endsWith(".xml")) {
                    externalParts++;
                }
                long part = 0;
                int n;
                java.io.ByteArrayOutputStream contentTypes = name.equals("[Content_Types].xml")
                        ? new java.io.ByteArrayOutputStream() : null;
                while ((n = zip.read(buffer)) > 0) {
                    if (contentTypes != null && part < 1_000_000) {
                        contentTypes.write(buffer, 0, n);
                    }
                    part += n;
                    total += n;
                    if (part > l.maxPartBytes() || total > l.maxInflatedBytes()) {
                        throw new Refused("inflated");
                    }
                    if (name.equals("[Content_Types].xml") && part > 1_000_000) {
                        throw new Refused("inflated");
                    }
                }
                if (contentTypes != null) {
                    if (part == 0) {
                        throw new Refused("not_xlsx");
                    }
                    String types = contentTypes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (types.contains("macroEnabled") || types.contains("vbaProject")) {
                        throw new Refused("macros");
                    }
                }
            }
        } catch (java.util.zip.ZipException z) {
            throw new Refused("not_xlsx");
        }
        if (entries == 0) {
            throw new Refused("not_xlsx");
        }
        return externalParts;
    }
}
