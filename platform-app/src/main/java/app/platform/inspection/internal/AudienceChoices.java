package app.platform.inspection.internal;

import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.Choice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the choice an approver sends at a step that asks for one (FA6G, ADR-0200): the places the template is
 * suggested for. A pure rule with no database, so it is tested alone.
 *
 * <p>The choice is a list of pairs of codes. A {@code location} pair names a location; a {@code department} pair names
 * a department of a location as {@code LOCATION/DEPARTMENT}. <strong>A location is required; a department is
 * optional</strong>: a location chosen with no department of it stands for the location alone. A department of a
 * location that was not chosen is refused, so a choice never says more than the approver ticked.
 *
 * <p>The words of a refusal are fixed and never repeat a code the person sent.
 */
final class AudienceChoices {

    /** The key of a pair that names a location. */
    static final String LOCATION = "location";
    /** The key of a pair that names a department of a location, as {@code LOCATION/DEPARTMENT}. */
    static final String DEPARTMENT = "department";

    static final String LOCATION_NEEDED = "Choose the location this template is for, then approve.";
    static final String NOT_VALID = "The choice sent is not valid. Open the choice again, then approve.";
    static final String NOT_OFFERED = "A location or department you chose is no longer offered. Open the choice "
            + "again, then approve.";

    /** The most places one choice holds (the same bound as a publish). */
    static final int MOST_PLACES = 20;

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,10}");

    /**
     * One place of a choice.
     *
     * @param location the location code
     * @param department the department code, or null for the location alone
     */
    record Place(String location, String department) {

        /** Prints nothing of the codes: they are the organization's own. */
        @Override
        public String toString() {
            return "Place[redacted]";
        }
    }

    /** The key of a pair that switches on one of the two options of the sheet (FA8): the value names the option. */
    static final String OPTION = "option";
    /** The value of an option pair that makes the template a reference only. */
    static final String VIEW_ONLY = "viewOnly";
    /** The value of an option pair that lets everybody who can read the sheet download it. */
    static final String ALLOW_DOWNLOAD = "allowDownload";

    static final String DEPARTMENT_NEEDED = "Choose at least one department of each location to publish to, then "
            + "confirm.";

    /**
     * What the window of the last approval of a change request sends: the places to publish to and the two options.
     *
     * @param places the places, each with a department
     * @param viewOnly whether the template is only a reference
     * @param allowDownload whether everybody who can read the sheet may download it
     */
    record Publish(List<Place> places, boolean viewOnly, boolean allowDownload) {

        /** Prints nothing of the codes: they are the organization's own. */
        @Override
        public String toString() {
            return "Publish[redacted]";
        }
    }

    private AudienceChoices() {
    }

    /**
     * Reads the choice of the last approval (FA8, ADR-0202): the places as for a suggestion, but a department is
     * required for every location (a template is published to departments), and the two options are pairs of the key
     * {@code option}.
     *
     * @throws ApprovalHookRefusal when the places are not valid, a location has no department, or an option is unknown
     */
    static Publish parsePublish(List<Choice> choices) {
        boolean viewOnly = false;
        boolean allowDownload = false;
        List<Choice> places = new ArrayList<>();
        for (Choice choice : choices) {
            if (!OPTION.equals(choice.key())) {
                places.add(choice);
            } else if (VIEW_ONLY.equals(choice.value())) {
                viewOnly = true;
            } else if (ALLOW_DOWNLOAD.equals(choice.value())) {
                allowDownload = true;
            } else {
                throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
            }
        }
        List<Place> parsed = parse(places);
        if (parsed.stream().anyMatch(place -> place.department() == null)) {
            throw new ApprovalHookRefusal("department_needed", DEPARTMENT_NEEDED);
        }
        return new Publish(parsed, viewOnly, allowDownload);
    }

    /**
     * The places a choice holds, locations in the order sent and, within a location, departments in the order sent.
     *
     * @throws ApprovalHookRefusal when no location was chosen, a code is malformed, a pair is repeated, a department
     *         belongs to a location that was not chosen, or there are more places than allowed
     */
    static List<Place> parse(List<Choice> choices) {
        Map<String, List<String>> byLocation = new LinkedHashMap<>();
        List<String[]> departments = new ArrayList<>();
        for (Choice choice : choices) {
            String key = choice.key();
            String value = choice.value();
            if (LOCATION.equals(key) && value != null && CODE.matcher(value).matches()) {
                if (byLocation.putIfAbsent(value, new ArrayList<>()) != null) {
                    throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
                }
            } else if (DEPARTMENT.equals(key) && value != null) {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || !CODE.matcher(parts[0]).matches() || !CODE.matcher(parts[1]).matches()) {
                    throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
                }
                departments.add(parts);
            } else {
                throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
            }
        }
        if (byLocation.isEmpty()) {
            throw new ApprovalHookRefusal("location_needed", LOCATION_NEEDED);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String[] pair : departments) {
            List<String> of = byLocation.get(pair[0]);
            if (of == null || !seen.add(pair[0] + "/" + pair[1])) {
                throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
            }
            of.add(pair[1]);
        }
        List<Place> places = new ArrayList<>();
        byLocation.forEach((location, parts) -> {
            if (parts.isEmpty()) {
                places.add(new Place(location, null));
            } else {
                parts.forEach(department -> places.add(new Place(location, department)));
            }
        });
        if (places.size() > MOST_PLACES) {
            throw new ApprovalHookRefusal("choice_invalid", NOT_VALID);
        }
        return List.copyOf(places);
    }
}
