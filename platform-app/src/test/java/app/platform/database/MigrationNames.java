package app.platform.database;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The migration naming and numbering rules of ADR-0008 (point 4) and ADR-0009: {@code V<nnn>__<snake_case>.sql}
 * for automatic migrations and {@code M<nnn>__<snake_case>.sql} for manual ones; three-digit numbers that are unique
 * and run without gaps from 001. Repeatable migrations and any other file are not allowed, because every change to
 * the schema must be a numbered, reviewable step that is never edited after release.
 */
final class MigrationNames {

    private static final String NAME = "[a-z0-9]+(?:_[a-z0-9]+)*";

    private MigrationNames() {
    }

    /**
     * Checks a folder listing.
     *
     * @param fileNames the names of all files in the folder
     * @param prefix {@code 'V'} for automatic migrations, {@code 'M'} for manual ones
     * @return one sentence per problem found; empty when the folder follows the rules
     */
    static List<String> problems(List<String> fileNames, char prefix) {
        Pattern pattern = Pattern.compile(prefix + "(\\d{3})__" + NAME + "\\.sql");
        List<String> problems = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (String fileName : fileNames) {
            Matcher matcher = pattern.matcher(fileName);
            if (!matcher.matches()) {
                problems.add("'" + fileName + "' does not match " + prefix + "<nnn>__<snake_case_name>.sql");
                continue;
            }
            int number = Integer.parseInt(matcher.group(1));
            if (!seen.add(number)) {
                problems.add("number " + matcher.group(1) + " is used by more than one file");
            }
            numbers.add(number);
        }

        List<Integer> sorted = numbers.stream().distinct().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != i + 1) {
                problems.add("numbers must run from 001 without gaps, but " + String.format("%03d", i + 1)
                        + " is missing before " + String.format("%03d", sorted.get(i)));
                break;
            }
        }
        return problems;
    }

    /**
     * Checks the content of one migration file.
     *
     * @return one sentence per problem found
     */
    static List<String> contentProblems(String fileName, String content) {
        List<String> problems = new ArrayList<>();
        if (content.isBlank()) {
            problems.add("'" + fileName + "' is empty");
        }
        if (content.indexOf('\r') >= 0) {
            problems.add("'" + fileName + "' contains carriage returns; migrations use LF line endings so that "
                    + "checksums are identical on every machine");
        }
        if (!content.isEmpty() && !content.endsWith("\n")) {
            problems.add("'" + fileName + "' does not end with a newline");
        }
        return problems;
    }
}
