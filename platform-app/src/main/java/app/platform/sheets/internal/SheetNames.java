package app.platform.sheets.internal;

import java.util.Locale;
import java.util.Set;

/**
 * The name of a worksheet tab in a file (SH3). A workbook allows at most 31 characters, none of {@code : \ / ? * [ ]},
 * no apostrophe at either end, no empty name and no two names alike in any case. The title of a sheet is text of the
 * organization: whatever it holds, the name written is always a valid one, made from it by dropping what is not
 * allowed and never by any other means.
 */
final class SheetNames {

    private static final int MOST = 31;
    private static final String FORBIDDEN = ":\\/?*[]";

    private SheetNames() {
    }

    /**
     * A valid, unused name.
     *
     * @param title the title of the sheet, or null
     * @param index the position of the sheet, from 0; the plain name {@code Sheet1} is made from it
     * @param used the names already taken, in lower case; the new one is added
     */
    static String of(String title, int index, Set<String> used) {
        String base = clean(title);
        if (base.isEmpty()) {
            base = "Sheet" + (index + 1);
        }
        String name = base;
        int copy = 2;
        // "History" is a name a spreadsheet program keeps for itself.
        while ("history".equals(name.toLowerCase(Locale.ROOT)) || !used.add(name.toLowerCase(Locale.ROOT))) {
            String suffix = " (" + copy++ + ")";
            name = base.substring(0, Math.min(base.length(), MOST - suffix.length())) + suffix;
        }
        return name;
    }

    private static String clean(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        title.codePoints().forEach(point -> {
            if (FORBIDDEN.indexOf(point) < 0 && point != 0xFFFE && point != 0xFFFF && !Character.isISOControl(point)
                    && Character.isDefined(point)) {
                out.appendCodePoint(point);
            }
        });
        String text = out.toString().strip();
        while (text.startsWith("'")) {
            text = text.substring(1).strip();
        }
        while (text.endsWith("'")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (text.length() > MOST) {
            int end = Character.isHighSurrogate(text.charAt(MOST - 1)) ? MOST - 1 : MOST;
            text = text.substring(0, end).strip();
        }
        return text;
    }
}
