package app.platform.sheets.internal;

/**
 * A file that cannot become a sheet, with a stable code and the words for the person (UI3B, ADR-0195). The words say
 * what is wrong and where by position (a row number, a column letter) and a limit by its number; they never repeat a
 * text of the file, a name of a sheet or of the file itself, and a library's own message is never used for them.
 * The stack trace is not filled in: a refusal is an expected answer, not a failure.
 */
final class ImportRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of refusal: the answer for a file over the size limit differs from the answer for a bad file. */
    enum Kind {
        INVALID, TOO_LARGE, BUSY
    }

    private final String code;
    private final Kind kind;

    ImportRefusal(String code, String words) {
        this(Kind.INVALID, code, words);
    }

    ImportRefusal(Kind kind, String code, String words) {
        super(words, null, false, false);
        this.code = code;
        this.kind = kind;
    }

    /** The file is not a workbook, whatever its name says. The library's own message is never repeated. */
    static ImportRefusal notXlsx() {
        return new ImportRefusal("not_xlsx", "The file cannot be read as an .xlsx workbook, whatever its name says.");
    }

    /** The workbook carries macros, which are never read (ADR-0253). */
    static ImportRefusal macros() {
        return new ImportRefusal("macros", "The workbook contains macros, which are never read. Save a copy as "
                + ".xlsx without macros and upload that.");
    }

    /** A limit of the file itself was reached (size, parts, cells). */
    static ImportRefusal tooLarge(String code, String words) {
        return new ImportRefusal(Kind.TOO_LARGE, code, words);
    }

    /** The unpacked size of the file, or of one of its parts, is over the limit. */
    static ImportRefusal unpackedTooLarge() {
        return tooLarge("inflated", "The file is larger than it is allowed to be once it is unpacked.");
    }

    /** A lower case code for tests and metrics. */
    String code() {
        return code;
    }

    Kind kind() {
        return kind;
    }

    /** The words shown to the person. */
    String words() {
        return getMessage();
    }
}
