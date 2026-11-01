package app.platform.integration.adapter;

/**
 * A file cannot be read as the connection says it should be (Sprint 29A, ADR-0240). It carries a stable problem and no
 * message and no cause: a parser's message could quote the file, and a file is data of the organization.
 */
public final class FileFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What is wrong with the file. The key is what is stored and counted. */
    public enum Problem {
        /** There is no row in it. */
        EMPTY("file_empty"),
        /** It is not text in the encoding the platform reads, or it holds characters that are not text. */
        NOT_TEXT("file_not_text"),
        /** It has more rows than the platform processes in one file. */
        TOO_MANY_ROWS("file_too_many_rows"),
        /** The first line does not name the columns the platform needs, or names one twice. */
        HEADER_INVALID("file_header_invalid"),
        /** A row is not shaped like the others, is too long, or has no identifier. */
        ROW_INVALID("file_row_invalid");

        private final String key;

        Problem(String key) {
            this.key = key;
        }

        /** The stable key. */
        public String key() {
            return key;
        }
    }

    private final transient Problem problem;

    /** Refuses a file for this reason. */
    public FileFormatException(Problem problem) {
        super(problem.key(), null, false, false);
        this.problem = problem;
    }

    /** What is wrong. */
    public Problem problem() {
        return problem;
    }
}
