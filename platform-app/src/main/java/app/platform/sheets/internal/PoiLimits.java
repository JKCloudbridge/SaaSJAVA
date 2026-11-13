package app.platform.sheets.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;

/**
 * Sets the library's own limits once (SH1B, ADR-0253): the ratio a part may inflate by, the size of an entry, the
 * number of entries, the size of a text and the size of an array. They are settings of the whole process, not of one
 * call, which is why they are set in one place and only the first call does it.
 *
 * <p>The library is never trusted as a guard: the archive scan of {@link XlsxArchive} refuses a file before the
 * library sees it, and these limits are the second line behind it. They are applied on first use rather than at
 * start, so a deployment that never reads a workbook never loads the library.
 */
final class PoiLimits {

    /** Whether the limits are set; the flag is the only state and it only ever goes from false to true. */
    private static final AtomicBoolean SET = new AtomicBoolean();

    private PoiLimits() {
    }

    /** Sets the limits from the settings of the upload, the first time it is called. */
    static void ensure(ImportProperties settings) {
        if (!SET.compareAndSet(false, true)) {
            return;
        }
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(settings.maxPartBytes());
        ZipSecureFile.setMaxFileCount(settings.maxEntries());
        ZipSecureFile.setMaxTextSize(settings.maxPartBytes());
        IOUtils.setByteArrayMaxOverride(settings.maxPartBytes());
    }
}
