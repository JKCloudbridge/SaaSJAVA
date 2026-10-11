package app.platform.expression.internal;

/**
 * Text operations whose cost is known. Searching a text for another one uses the Knuth-Morris-Pratt method, which
 * takes time in proportion to the two lengths and never more, so a text made to be slow ("aaaa...ab" searched in
 * "aaaa...") cannot make {@code CONTAINS} slow. Cutting a text counts characters as people see them (a code point), so
 * a cut never splits a pair of surrogates into two broken halves.
 */
final class TextSearch {

    private TextSearch() {
    }

    /** Whether {@code text} contains {@code needle}; a needle is never empty here (the empty text is no value). */
    static boolean contains(String text, String needle) {
        int n = text.length();
        int m = needle.length();
        if (m == 0) {
            return true;
        }
        if (m > n) {
            return false;
        }
        int[] failure = new int[m];
        for (int i = 1, k = 0; i < m; i++) {
            while (k > 0 && needle.charAt(i) != needle.charAt(k)) {
                k = failure[k - 1];
            }
            if (needle.charAt(i) == needle.charAt(k)) {
                k++;
            }
            failure[i] = k;
        }
        for (int i = 0, k = 0; i < n; i++) {
            while (k > 0 && text.charAt(i) != needle.charAt(k)) {
                k = failure[k - 1];
            }
            if (text.charAt(i) == needle.charAt(k)) {
                k++;
            }
            if (k == m) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code count} characters of the text (code points). */
    static String left(String text, int count) {
        int points = text.codePointCount(0, text.length());
        return count >= points ? text : text.substring(0, text.offsetByCodePoints(0, count));
    }

    /** The last {@code count} characters of the text (code points). */
    static String right(String text, int count) {
        int points = text.codePointCount(0, text.length());
        return count >= points ? text : text.substring(text.offsetByCodePoints(0, points - count));
    }

    /** {@code length} characters from the 1-based position {@code start}; empty when the start is past the end. */
    static String substring(String text, int start, int length) {
        int points = text.codePointCount(0, text.length());
        if (start > points) {
            return "";
        }
        int from = text.offsetByCodePoints(0, start - 1);
        int available = points - (start - 1);
        int to = text.offsetByCodePoints(from, Math.min(length, available));
        return text.substring(from, to);
    }
}
