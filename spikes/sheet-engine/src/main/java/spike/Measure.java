package spike;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * WP1.5. {@code gen <out>} writes a legitimate-looking workbook as close under the 5,000,000 byte cap as it can (random text, so it
 * does not pack well). {@code run <file> <runs>} opens it behind the pre-scan, walks every cell, and prints time and heap for each
 * run; the first run is the COLD one (nothing loaded yet). Run it with a heap cap to prove it fits.
 */
public final class Measure {

    private Measure() {
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("gen")) {
            gen(Path.of(args[1]));
            return;
        }
        byte[] file = Files.readAllBytes(Path.of(args[1]));
        int runs = Integer.parseInt(args[2]);
        PoiSafe.Limits limits = PoiSafe.Limits.standard();
        PoiSafe.configurePoi(limits);
        System.out.println("file " + file.length + " bytes; max heap " + Runtime.getRuntime().maxMemory() / 1_048_576 + " MB");
        for (int i = 1; i <= runs; i++) {
            long before = usedMb();
            long t0 = System.nanoTime();
            PoiSafe.Result r = PoiSafe.open(file, new PoiSafe.Limits(limits.maxFileBytes(), limits.maxPartBytes(),
                    limits.maxInflatedBytes(), limits.maxEntries(), limits.maxTextBytes(), limits.minInflateRatio(), 60_000),
                    true);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("run " + i + (i == 1 ? " (cold)" : "") + ": " + r.outcome() + ":" + r.reason() + " " + ms
                    + " ms; heap before " + before + " MB, peak so far " + peakHeapMb() + " MB");
            if (r.outcome() != PoiSafe.Outcome.ACCEPTED) {
                return;
            }
        }
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1_048_576;
    }

    private static long peakHeapMb() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak / 1_048_576;
    }

    private static void gen(Path out) throws Exception {
        SecureRandom rnd = new SecureRandom();
        int cells = 120_000;
        byte[] best = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder strings = new StringBuilder("<sst xmlns=\"" + MiniXlsx.MAIN + "\">");
            MiniXlsx f = new MiniXlsx();
            int columns = 20;
            int idx = 0;
            for (int row = 1; idx < cells; row++) {
                String[] rowCells = new String[columns];
                for (int c = 0; c < columns && idx < cells; c++, idx++) {
                    byte[] b = new byte[24];
                    rnd.nextBytes(b);
                    strings.append("<si><t>").append(HexFormat.of().formatHex(b)).append("</t></si>");
                    rowCells[c] = "<c r=\"" + colName(c) + row + "\" t=\"s\"><v>" + idx + "</v></c>";
                }
                f.row(row, java.util.Arrays.stream(rowCells).filter(java.util.Objects::nonNull).toArray(String[]::new));
            }
            f.sharedStrings(strings.append("</sst>").toString());
            byte[] bytes = f.bytes();
            System.out.println("cells " + cells + " -> " + bytes.length + " bytes");
            if (bytes.length <= 5_000_000) {
                best = bytes;
                if (bytes.length >= 4_800_000) {
                    break;
                }
                cells = (int) (cells * 4_950_000.0 / bytes.length);
            } else {
                cells = (int) (cells * 4_900_000.0 / bytes.length);
            }
        }
        Files.write(out, best);
        System.out.println("wrote " + out + " (" + best.length + " bytes)");
    }

    private static String colName(int c) {
        return String.valueOf((char) ('A' + c));
    }
}
