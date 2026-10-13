package app.platform.data.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the rows of a CSV file as bytes (Sprint 17, ADR-0123): UTF-8, one delimiter, values in double quotes that may
 * hold the delimiter, line breaks and doubled quotes (RFC 4180), and line ends of either kind. It is written for an
 * import that must resume: every row comes with the byte offset at which the next row starts, so the file can be read
 * again from there without reading the rows before it.
 *
 * <p>It is bounded on purpose, so that a hostile file costs a bounded amount of memory: a value, a row and the number
 * of values in a row have a largest size. A value or a row that is too large, and bytes that are not valid UTF-8, make
 * that one row a {@linkplain Row#problem() problem} and reading goes on with the next; a quote that is never closed
 * makes the rest of the file one row, which is larger than any row may be, so reading stops with a
 * {@link CsvFormatException}. A blank line is skipped. A byte order mark at the start of the file is skipped.
 *
 * <p>Nothing here is logged and no value is part of any message.
 */
final class CsvReader {

    /** Why the file cannot be read any further. */
    static final class CsvFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CsvFormatException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * One row.
     *
     * @param values the values as text, in order (empty when the row has a problem)
     * @param endOffset the byte offset at which the next row starts
     * @param problem why the row cannot be used, or null
     */
    record Row(List<String> values, long endOffset, String problem) {

        /** Copies the list. */
        Row {
            values = List.copyOf(values);
        }

        /** Prints nothing of the values. */
        @Override
        public String toString() {
            return "Row[" + values.size() + " values]";
        }
    }

    static final int MAX_VALUE_BYTES = 32 * 1024;
    static final int MAX_ROW_BYTES = 1024 * 1024;
    static final int MAX_VALUES = 200;

    private static final int QUOTE = '"';
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final InputStream in;
    private final int delimiter;
    private long offset;
    private int peeked = -2;

    /**
     * @param in the file from {@code startOffset}, which must be the start of a row
     * @param startOffset the byte offset the stream starts at
     * @param delimiter the delimiter, a single ASCII character
     */
    CsvReader(InputStream in, long startOffset, char delimiter) {
        this.in = in;
        this.offset = startOffset;
        this.delimiter = delimiter;
    }

    /**
     * The next row, or null at the end of the file.
     *
     * @throws CsvFormatException when a row is larger than any row may be (or a quote is never closed)
     * @throws IOException when the file cannot be read
     */
    Row next() throws IOException {
        if (offset == 0) {
            skipByteOrderMark();
        }
        while (true) {
            List<byte[]> cells = new ArrayList<>();
            ByteArrayOutputStream cell = new ByteArrayOutputStream();
            boolean quoted = false;
            boolean any = false;
            boolean tooLong = false;
            boolean tooMany = false;
            long rowBytes = 0;
            int c = read();
            if (c < 0) {
                return null;
            }
            while (true) {
                if (c >= 0) {
                    rowBytes++;
                    if (rowBytes > MAX_ROW_BYTES) {
                        throw new CsvFormatException("A row is larger than the most a row can be, or a quote is "
                                + "never closed.");
                    }
                }
                if (quoted) {
                    if (c < 0) {
                        throw new CsvFormatException("A quote is never closed.");
                    }
                    if (c == QUOTE) {
                        int next = peek();
                        if (next == QUOTE) {
                            read();
                            rowBytes++;
                            tooLong |= append(cell, QUOTE);
                        } else {
                            quoted = false;
                        }
                    } else {
                        tooLong |= append(cell, c);
                    }
                } else if (c == QUOTE && cell.size() == 0) {
                    quoted = true;
                    any = true;
                } else if (c == delimiter) {
                    any = true;
                    if (cells.size() < MAX_VALUES) {
                        cells.add(cell.toByteArray());
                    } else {
                        tooMany = true;
                    }
                    cell.reset();
                } else if (c < 0 || c == '\n' || c == '\r') {
                    if (c == '\r' && peek() == '\n') {
                        read();
                    }
                    if (!any && cell.size() == 0 && cells.isEmpty()) {
                        break; // a blank line: next row
                    }
                    if (cells.size() < MAX_VALUES) {
                        cells.add(cell.toByteArray());
                    } else {
                        tooMany = true;
                    }
                    return finish(cells, tooLong, tooMany);
                } else {
                    any = true;
                    tooLong |= append(cell, c);
                }
                c = read();
            }
        }
    }

    private Row finish(List<byte[]> cells, boolean tooLong, boolean tooMany) {
        if (tooMany) {
            return new Row(List.of(), offset, "The row has more values than a row can have.");
        }
        if (tooLong) {
            return new Row(List.of(), offset, "A value is longer than a value can be.");
        }
        List<String> values = new ArrayList<>();
        for (byte[] bytes : cells) {
            try {
                values.add(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
            } catch (CharacterCodingException e) {
                return new Row(List.of(), offset, "The row is not valid UTF-8.");
            }
        }
        return new Row(values, offset, null);
    }

    /** Adds a byte to the value; true when the value has become larger than a value can be (the byte is dropped). */
    private static boolean append(ByteArrayOutputStream cell, int c) {
        if (cell.size() >= MAX_VALUE_BYTES) {
            return true;
        }
        cell.write(c);
        return false;
    }

    private void skipByteOrderMark() throws IOException {
        int first = read();
        if (first != (BOM[0] & 0xFF)) {
            unread(first);
            return;
        }
        int second = read();
        int third = second == (BOM[1] & 0xFF) ? read() : -1;
        if (second == (BOM[1] & 0xFF) && third == (BOM[2] & 0xFF)) {
            return;
        }
        throw new CsvFormatException("The file does not start like a UTF-8 text.");
    }

    private int read() throws IOException {
        int c;
        if (peeked != -2) {
            c = peeked;
            peeked = -2;
        } else {
            c = in.read();
        }
        if (c >= 0) {
            offset++;
        }
        return c;
    }

    private int peek() throws IOException {
        if (peeked == -2) {
            peeked = in.read();
        }
        return peeked;
    }

    private void unread(int c) {
        if (c >= 0) {
            offset--;
        }
        peeked = c;
    }
}
