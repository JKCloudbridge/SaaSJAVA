package app.platform.inspection.internal;

import app.platform.inspection.internal.RegisterViews.RegisterCompletion;
import app.platform.inspection.internal.RegisterViews.RegisterInput;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the register form accepts (FA8, ADR-0202): which parts are typed by whom, how long each may be, and what text is
 * refused. A pure rule with no database, so it is tested alone. The words of a refusal are fixed and name the box,
 * never
 * the text a person typed. The text rules are those of every other text of a change request ({@link ChangeText}): no
 * markup, no control characters, no marks that change the direction of the text.
 *
 * <p>Two people write the form, each their own part. The person who raises the request writes the top once: the request
 * number, what is asked, the basis, the horizontal deployment and the format that stops being used. The people asked to
 * decide the request complete the bottom while it is open. The new revision and the day it was published are written by
 * nobody: the platform stamps them when the request is published.
 */
final class RegisterRules {

    static final int REQUEST_NO = 30;
    static final int CHANGE_REQUESTED = 4000;
    static final int BASIS = 4000;
    static final int HORIZONTAL = 2000;
    static final int OBSOLETED = 1000;
    static final int DEPARTMENT = 1000;
    static final int APPROVED_BY = 2000;
    static final int SIGNATURE = 500;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** The first line of what is asked, cut to the length of a title, is what the request is called. */
    private static final int TITLE_CHARS = 120;

    /**
     * What the requester typed, checked and cleaned.
     *
     * @param requestNo the number
     * @param changeRequested what is asked
     * @param basisForChange why, or null
     * @param horizontalDeployment where else, or null
     * @param obsoletedFormat what stops being used, or null
     * @param obsoletedEffectiveOn the day it stops, or null
     */
    record Raised(String requestNo, String changeRequested, String basisForChange, String horizontalDeployment,
            String obsoletedFormat, LocalDate obsoletedEffectiveOn) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Raised[redacted]";
        }
    }

    /**
     * What the approvers completed, checked and cleaned.
     *
     * @param department the department, or null
     * @param obsoletedFormat what stops being used, or null
     * @param obsoletedEffectiveOn the day it stops, or null
     * @param approvedBy who approved, or null
     * @param signatureNote the signature box, or null
     * @param effectiveOn the day the new revision takes effect, or null
     * @param serverUpdated YES, NO or null
     * @param masterListUpdated YES, NO or null
     */
    record Completed(String department, String obsoletedFormat, LocalDate obsoletedEffectiveOn, String approvedBy,
            String signatureNote, LocalDate effectiveOn, String serverUpdated, String masterListUpdated) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Completed[redacted]";
        }
    }

    /**
     * A thing checked: the value when nothing is wrong, otherwise the problems by field.
     *
     * @param value the checked value, or null when there are problems
     * @param problems the words by field name
     * @param <T> what was checked
     */
    record Checked<T>(T value, Map<String, List<String>> problems) {

        boolean accepted() {
            return problems.isEmpty();
        }
    }

    private RegisterRules() {
    }

    /** Checks what a requester typed. */
    static Checked<Raised> raise(RegisterInput input) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        text(problems, "requestNo", "request number", input.requestNo(), REQUEST_NO, true, false);
        text(problems, "changeRequested", "change requested", input.changeRequested(), CHANGE_REQUESTED, true, true);
        text(problems, "basisForChange", "basis for change", input.basisForChange(), BASIS, false, true);
        text(problems, "horizontalDeployment", "horizontal deployment", input.horizontalDeployment(), HORIZONTAL,
                false, true);
        text(problems, "obsoletedFormat", "format or document obsoleted", input.obsoletedFormat(), OBSOLETED, false,
                true);
        LocalDate day = day(problems, "obsoletedEffectiveOn", "day the format or document is obsoleted",
                input.obsoletedEffectiveOn());
        if (!problems.isEmpty()) {
            return new Checked<>(null, problems);
        }
        return new Checked<>(new Raised(ChangeText.clean(input.requestNo()),
                ChangeText.clean(input.changeRequested()), blankToNull(input.basisForChange()),
                blankToNull(input.horizontalDeployment()), blankToNull(input.obsoletedFormat()), day), Map.of());
    }

    /** Checks what the approvers completed. */
    static Checked<Completed> complete(RegisterCompletion input) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        text(problems, "department", "department", input.department(), DEPARTMENT, false, true);
        text(problems, "obsoletedFormat", "format or document obsoleted", input.obsoletedFormat(), OBSOLETED, false,
                true);
        LocalDate obsoletedOn = day(problems, "obsoletedEffectiveOn", "day the format or document is obsoleted",
                input.obsoletedEffectiveOn());
        text(problems, "approvedBy", "approved by", input.approvedBy(), APPROVED_BY, false, true);
        text(problems, "signatureNote", "signature", input.signatureNote(), SIGNATURE, false, true);
        LocalDate effective = day(problems, "effectiveOn", "effective date", input.effectiveOn());
        String server = answer(problems, "serverUpdated", "answer about the server", input.serverUpdated());
        String master = answer(problems, "masterListUpdated", "answer about the master list",
                input.masterListUpdated());
        if (!problems.isEmpty()) {
            return new Checked<>(null, problems);
        }
        return new Checked<>(new Completed(blankToNull(input.department()), blankToNull(input.obsoletedFormat()),
                obsoletedOn, blankToNull(input.approvedBy()), blankToNull(input.signatureNote()), effective, server,
                master), Map.of());
    }

    /** What the request is called: the first line of what is asked, cut to the length of a title. */
    static String titleOf(String changeRequested) {
        String clean = ChangeText.clean(changeRequested);
        int end = clean.indexOf('\n');
        String line = (end < 0 ? clean : clean.substring(0, end)).strip();
        return line.length() <= TITLE_CHARS ? line : line.substring(0, TITLE_CHARS - 1).stripTrailing() + "…";
    }

    /** A day as the register writes it. */
    static String show(LocalDate day) {
        return day == null ? null : ISO.format(day);
    }

    // ---- the checks of one box ----

    private static void text(Map<String, List<String>> problems, String field, String what, String value, int most,
            boolean required, boolean lines) {
        if (!required && (value == null || value.isBlank())) {
            return;
        }
        ChangeText.problem(what, value, most, lines).ifPresent(words -> problems.put(field, List.of(words)));
    }

    private static LocalDate day(Map<String, List<String>> problems, String field, String what, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Optional<LocalDate> parsed = RegisterLayout.parseDate(value);
        if (parsed.isEmpty()) {
            problems.put(field, List.of("Write the " + what + " as year-month-day, for example 2026-12-31."));
            return null;
        }
        return parsed.get();
    }

    private static String answer(Map<String, List<String>> problems, String field, String what, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.strip().toUpperCase(Locale.ROOT);
        if (!upper.equals("YES") && !upper.equals("NO")) {
            problems.put(field, List.of("The " + what + " is Yes or No."));
            return null;
        }
        return upper;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : ChangeText.clean(value);
    }
}
