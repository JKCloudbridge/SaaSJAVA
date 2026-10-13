package app.platform.data.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words of a text, for the search (Sprint 17, ADR-0124): the one function that turns a stored value into the words
 * the search table keeps, and a typed search into the words it looks for, so that both sides always agree.
 *
 * <p>A text is decomposed and the accents are dropped (so "José" is found by "jose"), made lower case, and cut into
 * runs of letters and digits. A word has at least two characters (a single digit is kept) and at most forty. An e-mail
 * address is kept whole as well (so it can be found by the address), and a telephone number as its digits run
 * together. A value has at most sixty-four words; what comes after is not searched. Nothing here is logged.
 */
final class SearchTokens {

    /** The most words kept for one value of one field. */
    static final int MAX_WORDS = 64;

    /** The longest word. */
    static final int MAX_WORD = 40;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern RUNS = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern NUMBER_CHARS = Pattern.compile("[0-9+()\\-./ ]{3,40}");

    private SearchTokens() {
    }

    /** The words of a stored value, without repeats, in the order they appear. */
    static Set<String> wordsOf(String value) {
        Set<String> words = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return words;
        }
        String folded = fold(value);
        String stripped = folded.strip();
        if (ADDRESS.matcher(stripped).matches() && stripped.length() <= MAX_WORD + 24) {
            addWhole(words, stripped);
        }
        var matcher = RUNS.matcher(folded);
        while (matcher.find() && words.size() < MAX_WORDS) {
            String run = matcher.group();
            if (run.length() >= 2 || Character.isDigit(run.charAt(0))) {
                words.add(run.length() <= MAX_WORD ? run : run.substring(0, MAX_WORD));
            }
        }
        if (NUMBER_CHARS.matcher(stripped).matches()) {
            String digits = stripped.replaceAll("[^0-9]", "");
            if (digits.length() >= 3 && digits.length() <= MAX_WORD && words.size() < MAX_WORDS) {
                words.add(digits);
            }
        }
        return words;
    }

    /**
     * The words of a typed search. The last one is a beginning (the person is still typing), the others are whole
     * words.
     *
     * @param text what was typed
     * @param most the most words kept; the rest of the text is not searched
     */
    static List<Word> queryWords(String text, int most) {
        List<String> runs = new ArrayList<>();
        var matcher = RUNS.matcher(fold(text == null ? "" : text));
        while (matcher.find() && runs.size() < most) {
            String run = matcher.group();
            if (run.length() >= 2 || Character.isDigit(run.charAt(0))) {
                runs.add(run.length() <= MAX_WORD ? run : run.substring(0, MAX_WORD));
            }
        }
        List<Word> words = new ArrayList<>();
        for (int index = 0; index < runs.size(); index++) {
            words.add(new Word(runs.get(index), index == runs.size() - 1));
        }
        return words;
    }

    /**
     * A word of a search.
     *
     * @param text the word
     * @param beginning whether it matches every word that starts with it (the last word typed)
     */
    record Word(String text, boolean beginning) {

        /**
         * A text that is greater than every word that starts with this one, byte by byte: the word followed by the
         * last code point there is.
         */
        String upperBound() {
            return text + Character.toString(Character.MAX_CODE_POINT);
        }

        /** Prints the shape only: the word was typed by a person. */
        @Override
        public String toString() {
            return "Word[" + (beginning ? "beginning" : "whole") + "]";
        }
    }

    private static void addWhole(Set<String> words, String address) {
        if (address.length() <= 64) {
            words.add(address);
        }
    }

    private static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
