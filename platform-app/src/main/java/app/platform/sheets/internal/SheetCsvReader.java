package app.platform.sheets.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads a comma, semicolon or tab separated file into a {@link RawSheet} (UI3B, ADR-0195). UTF-8 only: a file in
 * another encoding is refused with the way to save it, because guessing an encoding changes people's words without a
 * word. A leading byte order mark is dropped. Quoted values may hold the separator, line breaks and a doubled quote.
 * The delimiter is the one of the three that the first line holds most outside quotes (a comma on a tie).
 *
 * <p>The same counts bound it as the workbook reader: a cell is a cell, whatever the format. A value is held at most
 * one character above the limit of a cell. Nothing of the file is put in a message.
 */
final class SheetCsvReader {

    private final ImportProperties settings;
    private final SheetProperties.Limits limits;

    SheetCsvReader(ImportProperties settings, SheetProperties.Limits limits) {
        this.settings = settings;
        this.limits = limits;
    }

    RawSheet read(byte[] file) {
        for (byte b : file) {
            if (b == 0) {
                throw new ImportRefusal("not_csv", "The file is not a text file, whatever its name says.");
            }
        }
        if (file.length >= 4 && file[0] == 'P' && file[1] == 'K' && file[2] == 3 && file[3] == 4) {
            throw new ImportRefusal("not_csv", "The file is a workbook, not a text file. Upload it as .xlsx.");
        }
        String text = decode(file);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        char delimiter = delimiterOf(text);
        RawSheet sheet = new RawSheet();
        int row = 0;
        int column = 0;
        int read = 0;
        boolean quoted = false;
        boolean wasQuoted = false;
        StringBuilder value = new StringBuilder();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        append(value, '"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    append(value, c);
                }
                continue;
            }
            if (c == '"' && value.length() == 0 && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == delimiter) {
                read = put(sheet, row, column, value, read);
                column++;
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                read = put(sheet, row, column, value, read);
                row++;
                column = 0;
                wasQuoted = false;
            } else {
                append(value, c);
            }
        }
        if (quoted) {
            throw new ImportRefusal("not_csv", "A quoted value in the file is never closed.");
        }
        put(sheet, row, column, value, read);
        return sheet;
    }

    private static String decode(byte[] file) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString();
        } catch (CharacterCodingException e) {
            throw new ImportRefusal("not_utf8", "The file is not written in UTF-8. Save it as \"CSV UTF-8\" and "
                    + "upload that.");
        }
    }

    private void append(StringBuilder to, char c) {
        if (to.length() <= limits.maxCellChars()) {
            to.append(c);
        }
    }

    private int put(RawSheet sheet, int row, int column, StringBuilder value, int read) {
        int count = read + 1;
        if (count > settings.maxCells()) {
            throw new ImportRefusal(ImportRefusal.Kind.TOO_LARGE, "cells", "The file holds more than "
                    + settings.maxCells() + " values, which is more than can be read.");
        }
        String text = value.toString();
        value.setLength(0);
        if (!text.isBlank()) {
            sheet.put(row, column, new RawSheet.Cell(text, false, false, 0));
        }
        return count;
    }

    /** The one of the three separators the first line holds most of, outside quotes. */
    static char delimiterOf(String text) {
        int comma = 0;
        int semicolon = 0;
        int tab = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length() && i < 8192; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                switch (c) {
                    case ',' -> comma++;
                    case ';' -> semicolon++;
                    case '\t' -> tab++;
                    default -> {
                    }
                }
            }
        }
        if (semicolon > comma && semicolon >= tab) {
            return ';';
        }
        if (tab > comma) {
            return '\t';
        }
        return ',';
    }
}
