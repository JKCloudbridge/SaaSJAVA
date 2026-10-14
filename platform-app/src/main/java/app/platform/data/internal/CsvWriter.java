package app.platform.data.internal;

import java.util.List;

/**
 * Writes the lines of the report of an import (Sprint 17, ADR-0123). A value is put in quotes when it holds a
 * delimiter, a quote or a line break, and a quote inside it is doubled. The report is opened in a spreadsheet by the
 * person who sent the file, and a spreadsheet runs a value that starts with {@code =}, {@code +}, {@code -} or
 * {@code @} as a formula, so such a value gets an apostrophe in front of it ("CSV injection").
 */
final class CsvWriter {

    private CsvWriter() {
    }

    /** One line, without the line end. */
    static String line(List<String> values) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(quoted(safe(values.get(index))));
        }
        return line.toString();
    }

    /** The value with an apostrophe in front when a spreadsheet would run it. */
    static String safe(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    private static String quoted(String value) {
        boolean needed = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf(';') >= 0 || value.indexOf('\t') >= 0;
        return needed ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
