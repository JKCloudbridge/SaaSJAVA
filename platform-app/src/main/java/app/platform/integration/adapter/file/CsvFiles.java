package app.platform.integration.adapter.file;

import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.FileFormatException;
import app.platform.integration.adapter.FileFormatException.Problem;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.PayloadException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Comma separated values, read and written by hand (Sprint 29A, ADR-0240). The rules are strict and small so that a file
 * is either read as the connection says or refused as a whole: UTF-8 text only (a leading byte order mark is accepted),
 * double quotes for a value that holds a comma, a quote or a line break, a first line that names the columns, every
 * row as long as the first, bounds on the number of columns, the number of rows and the length of a value. Nothing a
 * file says is ever run, and a refusal never repeats what the file held.
 */
final class CsvFiles {

    static final int MAX_COLUMNS = 200;
    static final int MAX_CELL = 10_000;
    static final int MAX_ID = 200;
    static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_. -]{0,63}");

    private CsvFiles() {
    }

    // ---- reading ----

    static List<CanonicalEntity> read(byte[] content, String kind, String idColumn, String operationColumn,
            int maxRows) {
        String text = decode(content);
        Records records = new Records(text);
        List<String> header = records.next();
        if (header == null) {
            throw new FileFormatException(Problem.EMPTY);
        }
        int idIndex = -1;
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip();
            if (!COLUMN.matcher(name).matches() || !seen.add(name)) {
                throw new FileFormatException(Problem.HEADER_INVALID);
            }
            if (name.equals(idColumn)) {
                idIndex = i;
            }
            names.add(name);
        }
        if (idIndex < 0) {
            throw new FileFormatException(Problem.HEADER_INVALID);
        }
        List<CanonicalEntity> rows = new ArrayList<>();
        for (List<String> record = records.next(); record != null; record = records.next()) {
            if (rows.size() >= maxRows) {
                throw new FileFormatException(Problem.TOO_MANY_ROWS);
            }
            if (record.size() != names.size()) {
                throw new FileFormatException(Problem.ROW_INVALID);
            }
            String id = record.get(idIndex).strip();
            if (id.isEmpty() || id.length() > MAX_ID) {
                throw new FileFormatException(Problem.ROW_INVALID);
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String value = record.get(i);
                if (i == idIndex || names.get(i).equals(operationColumn) || value.isEmpty()) {
                    continue;
                }
                fields.put(names.get(i), value);
            }
            rows.add(new CanonicalEntity(kind, id, fields));
        }
        if (rows.isEmpty()) {
            throw new FileFormatException(Problem.EMPTY);
        }
        return rows;
    }

    /** Strict UTF-8, no byte order mark, no control character other than tab and line breaks. */
    static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException notText) {
            throw new FileFormatException(Problem.NOT_TEXT);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0x7f) {
                throw new FileFormatException(Problem.NOT_TEXT);
            }
        }
        return text;
    }

    /** The records of a text, one at a time. A blank line is not a record. */
    private static final class Records {

        private final String text;
        private int position;

        Records(String text) {
            this.text = text;
        }

        List<String> next() {
            while (position < text.length()) {
                List<String> fields = record();
                if (fields.size() == 1 && fields.get(0).isEmpty() && !lastWasQuoted) {
                    continue;
                }
                return fields;
            }
            return null;
        }

        private boolean lastWasQuoted;

        private List<String> record() {
            List<String> fields = new ArrayList<>();
            lastWasQuoted = false;
            while (true) {
                if (fields.size() >= MAX_COLUMNS) {
                    throw new FileFormatException(Problem.ROW_INVALID);
                }
                fields.add(field());
                if (position >= text.length()) {
                    return fields;
                }
                char c = text.charAt(position);
                if (c == ',') {
                    position++;
                    if (position >= text.length()) {
                        fields.add("");
                        return fields;
                    }
                    continue;
                }
                // A line break ends the record; a "\r\n" counts once.
                position++;
                if (c == '\r' && position < text.length() && text.charAt(position) == '\n') {
                    position++;
                }
                return fields;
            }
        }

        private String field() {
            StringBuilder value = new StringBuilder();
            if (position < text.length() && text.charAt(position) == '"') {
                lastWasQuoted = true;
                position++;
                while (true) {
                    if (position >= text.length()) {
                        throw new FileFormatException(Problem.ROW_INVALID);
                    }
                    char c = text.charAt(position++);
                    if (c == '"') {
                        if (position < text.length() && text.charAt(position) == '"') {
                            value.append('"');
                            position++;
                            continue;
                        }
                        break;
                    }
                    value.append(c);
                    if (value.length() > MAX_CELL) {
                        throw new FileFormatException(Problem.ROW_INVALID);
                    }
                }
                if (position < text.length()) {
                    char after = text.charAt(position);
                    if (after != ',' && after != '\n' && after != '\r') {
                        throw new FileFormatException(Problem.ROW_INVALID);
                    }
                }
                return value.toString();
            }
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ',' || c == '\n' || c == '\r') {
                    break;
                }
                if (c == '"') {
                    throw new FileFormatException(Problem.ROW_INVALID);
                }
                value.append(c);
                position++;
                if (value.length() > MAX_CELL) {
                    throw new FileFormatException(Problem.ROW_INVALID);
                }
            }
            return value.toString();
        }
    }

    // ---- writing ----

    static byte[] write(String idColumn, String operationColumn, Operation operation, CanonicalEntity entity) {
        StringBuilder header = new StringBuilder();
        StringBuilder row = new StringBuilder();
        append(header, idColumn);
        append(row, entity.externalId() == null ? "" : entity.externalId());
        header.append(',');
        row.append(',');
        append(header, operationColumn);
        append(row, operation.name().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, Object> field : entity.fields().entrySet()) {
            String name = field.getKey();
            if (name.equals(idColumn) || name.equals(operationColumn) || !COLUMN.matcher(name).matches()) {
                throw new PayloadException("A field cannot be written as a column.");
            }
            header.append(',');
            row.append(',');
            append(header, name);
            append(row, text(field.getValue()));
        }
        return header.append('\n').append(row).append('\n').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The value as text: numbers in plain form, dates and times in ISO form. */
    static String text(Object value) {
        String text = switch (value) {
            case BigDecimal number -> number.toPlainString();
            case Boolean bool -> bool ? "true" : "false";
            case LocalDate date -> date.toString();
            case LocalTime time -> time.toString();
            case Instant instant -> instant.toString();
            default -> value.toString();
        };
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0x7f) {
                throw new PayloadException("A value cannot be written to a file.");
            }
        }
        return text;
    }

    private static void append(StringBuilder out, String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.startsWith(" ") || value.endsWith(" ");
        if (!quote) {
            out.append(value);
            return;
        }
        out.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
}
