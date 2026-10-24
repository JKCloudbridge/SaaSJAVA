package app.platform.sheets.internal;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Reads a spreadsheet file into a checked grid (UI3B, ADR-0195): the reader of its format, the builder, then the
 * validator every grid passes. Pure with respect to the database: it reads bytes and answers a grid or a refusal, so
 * what it gives can be tested without one, and the module's one place for who may change a sheet stays
 * {@link SheetOwners}.
 *
 * <p>The number of files read at the same moment on one instance is bounded ({@code max-concurrent}): a file that
 * arrives when all places are taken is told to try again and nothing about it is read. A file is held in memory only
 * while it is read; nothing is written to disk and nothing is kept.
 */
@Component
final class SpreadsheetImporter {

    /** The kinds of file that can be read. */
    enum Format {
        XLSX, CSV;

        static Optional<Format> of(String name) {
            if (name == null) {
                return Optional.empty();
            }
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "xlsx" -> Optional.of(XLSX);
                case "csv" -> Optional.of(CSV);
                default -> Optional.empty();
            };
        }
    }

    /**
     * A file made into a grid.
     *
     * @param checked the grid in its canonical form
     * @param report what the person is told
     */
    record Imported(SheetGridValidator.Checked checked, SheetImportBuilder.Report report) {
    }

    private final ImportProperties settings;
    private final SheetProperties.Limits limits;
    private final SheetGridValidator validator;
    private final Semaphore places;

    SpreadsheetImporter(ImportProperties settings, SheetProperties properties, SheetGridValidator validator) {
        this.settings = settings;
        this.limits = properties.limits();
        this.validator = validator;
        this.places = new Semaphore(settings.maxConcurrent());
    }

    /** The places that bound the files read at once; a test takes them all to see the answer for one more. */
    Semaphore places() {
        return places;
    }

    /** The size a file may have, for the endpoint that reads the bytes. */
    int maxFileBytes() {
        return settings.maxFileBytes();
    }

    /**
     * Reads the file.
     *
     * @throws ImportRefusal when the file cannot become a sheet; the words are safe to show
     */
    Imported read(byte[] file, Format format, SheetImportBuilder.Options options) {
        if (file.length > settings.maxFileBytes()) {
            throw tooLarge(settings.maxFileBytes());
        }
        if (file.length == 0) {
            throw new ImportRefusal("empty_file", "The file is empty.");
        }
        if (!places.tryAcquire()) {
            throw new ImportRefusal(ImportRefusal.Kind.BUSY, "busy", "Other files are being read right now. Try "
                    + "again in a moment.");
        }
        try {
            RawSheet raw = format == Format.XLSX ? new XlsxReader(settings, limits).read(file)
                    : new SheetCsvReader(settings, limits).read(file);
            SheetImportBuilder.Built built = new SheetImportBuilder(limits).build(raw, options);
            SheetGridValidator.Result result = validator.check(built.grid());
            if (!result.accepted()) {
                // The words of a problem are fixed and repeat nothing; the place names generated identifiers and is
                // left out.
                throw new ImportRefusal("grid_invalid", "The sheet made from this file is not accepted: "
                        + result.problems().get(0).message());
            }
            return new Imported(result.checked(), built.report());
        } finally {
            places.release();
        }
    }

    static ImportRefusal tooLarge(int most) {
        return new ImportRefusal(ImportRefusal.Kind.TOO_LARGE, "file_too_large", "The file is larger than the "
                + "allowed size of " + most + " bytes.");
    }
}
