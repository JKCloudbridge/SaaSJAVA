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
