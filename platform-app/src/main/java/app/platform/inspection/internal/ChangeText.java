package app.platform.inspection.internal;

import java.util.Optional;

/**
 * The rules for the text a person types into a change request: the title, a comment and the reason for declining (FA5,
 * ADR-0191). Pure logic. The text is the organization's own, so the words of a refusal say what is expected and never
 * repeat what was typed.
 *
 * <p>A text is plain: it is refused when it holds markup (the characters &lt; and &gt;), a control character other
 * than a line break (and a line break only where lines are allowed), or a mark that reverses the direction of text,
 * which can make one sentence read as another. It is also refused when empty or too long. Nothing is cut or
 * changed: what was accepted is what is stored, apart from the spaces at its ends and the form of a line break.
 */
final class ChangeText {

    private ChangeText() {
    }

    /** The text as it is stored: the ends stripped and every line break a single line feed. */
    static String clean(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /**
     * What is wrong with the text, in words, or empty when it is acceptable.
     *
     * @param what the name of the text for the sentence: {@code title}, {@code comment} or {@code reason}
     * @param text the text as typed
     * @param most the most characters it may have
     * @param lines whether line breaks are allowed
     */
    static Optional<String> problem(String what, String text, int most, boolean lines) {
        String clean = clean(text);
        if (clean.isEmpty()) {
            return Optional.of("Write the " + what + ".");
        }
        if (clean.length() > most) {
            return Optional.of("The " + what + " is at most " + most + " characters.");
        }
        if (clean.indexOf('<') >= 0 || clean.indexOf('>') >= 0) {
            return Optional.of("The " + what + " is plain text: leave out the characters < and >.");
        }
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '\n' && lines) {
                continue;
            }
            if (Character.isISOControl(c)) {
                return Optional.of(lines
                        ? "The " + what + " may use line breaks and no other control characters."
                        : "The " + what + " is one line.");
            }
            if (reversesDirection(c)) {
                return Optional.of("The " + what + " holds a mark that changes the direction of the text; "
                        + "leave it out.");
            }
        }
        return Optional.empty();
    }

    /** The marks that change the direction of text (they can make a line read differently from how it is written). */
    private static boolean reversesDirection(char c) {
        return c == '؜' || c == '‎' || c == '‏' || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩');
    }
}
