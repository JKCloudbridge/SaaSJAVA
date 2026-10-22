package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.TemplateViews.AudienceDepartment;
import app.platform.inspection.internal.TemplateViews.AudienceLocation;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.GroupMembers;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns "Location, then Department" into the group of people it means (FA4, ADR-0188, ADR-0190). The mapping is the
 * records of the shipped object {@code DepartmentGroup}, kept by the organization's administrator; the person who
 * publishes may not read that object, so it is read as the platform, and only to find the group. Every pair is resolved
 * and checked before anything is shared, so a publish to several departments is all or nothing.
 *
 * <p>Why a pair is refused, in words: no group is set up for it, more than one is (the database cannot make a pair
 * unique, so it is said here), the group was removed, or the group has no members yet (a publish to nobody would look
 * like it worked). The words never name the location, the department or the group.
 */
@Component
class DepartmentAudiences {

    static final String NO_GROUP = "No group is set up for this department yet.";
    static final String MANY_GROUPS = "More than one group is set up for this department. Ask your administrator to "
            + "keep one.";
    static final String GROUP_GONE = "The group set up for this department no longer exists.";
    static final String GROUP_EMPTY = "The group set up for this department has no members yet.";
    /** More than this many mapping records for one pair means the same as two. */
    private static final int LOOK_AT = 2;
    /** The most mapping records the options read: an organization has one per department of a location. */
    private static final int MOST_MAPPINGS = 1000;

    /**
     * A pair that was resolved to the group it means.
     *
     * @param pick the location and department
     * @param groupId the group
     */
    record Resolved(AudiencePick pick, UUID groupId) {
    }

    private final BackgroundRecords records;
    private final GroupMembers groups;
    private final Metadata metadata;

    DepartmentAudiences(BackgroundRecords records, GroupMembers groups, Metadata metadata) {
        this.records = records;
        this.groups = groups;
        this.metadata = metadata;
    }

    /**
     * The group of each pair, in the order asked, all or nothing.
     *
     * @throws ApiException {@code VALIDATION_ERROR} whose fields name the first pair that cannot be published to
     */
    List<Resolved> resolve(List<AudiencePick> picks) {
        List<Resolved> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < picks.size(); index++) {
            AudiencePick pick = picks.get(index);
            if (!seen.add(pick.location() + "/" + pick.department())) {
                throw refused(index, "This department is listed twice.");
            }
            resolved.add(new Resolved(pick, groupOf(index, pick)));
        }
        return resolved;
    }

    private UUID groupOf(int index, AudiencePick pick) {
        QueryFilterNode filter = new QueryFilterNode("AND", List.of(
                new QueryFilterNode("EQ", null, Templates.LOCATION, List.of(pick.location())),
                new QueryFilterNode("EQ", null, Templates.DEPARTMENT, List.of(pick.department()))), null, null);
        List<DataRecord> found = records.scan(Templates.DEPARTMENT_OBJECT, filter, null, LOOK_AT);
        if (found.isEmpty()) {
            throw refused(index, NO_GROUP);
        }
        if (found.size() > 1) {
            throw refused(index, MANY_GROUPS);
        }
        UUID group = groupId(found.get(0));
        if (group == null || !groups.exists(group)) {
            throw refused(index, group == null ? NO_GROUP : GROUP_GONE);
        }
        if (groups.membersOf(group).isEmpty()) {
            throw refused(index, GROUP_EMPTY);
        }
        return group;
    }

    /**
     * What the publish dialog offers: every location of the organization's list with the departments that go with it,
     * and for each whether a group is set up. Read as the platform (the person may not read the mapping), and only for
     * a person who holds the ability to publish (the caller has checked).
     */
    AudienceOptionsView options() {
        ObjectDefinition object = metadata.object(Templates.DEPARTMENT_OBJECT)
                .orElseThrow(() -> ApiException.notFound("This list does not exist."));
        FieldDefinition location = object.field(Templates.LOCATION).orElseThrow();
        FieldDefinition department = object.field(Templates.DEPARTMENT).orElseThrow();
        PicklistConfiguration locations = (PicklistConfiguration) location.configuration();
        PicklistConfiguration departments = (PicklistConfiguration) department.configuration();
        Map<String, String> departmentLabels = new LinkedHashMap<>();
        departments.values().stream().filter(PicklistValue::active)
                .forEach(value -> departmentLabels.put(value.value(), value.label()));
        Set<String> configured = configuredPairs();
        List<AudienceLocation> result = new ArrayList<>();
        for (PicklistValue place : locations.values()) {
            if (!place.active()) {
                continue;
            }
            List<String> allowed = departments.dependency() == null ? List.copyOf(departmentLabels.keySet())
                    : departments.dependency().allowed().getOrDefault(place.value(), List.of());
            List<AudienceDepartment> offered = new ArrayList<>();
            for (String code : allowed) {
                if (departmentLabels.containsKey(code)) {
                    offered.add(new AudienceDepartment(code, departmentLabels.get(code),
                            configured.contains(place.value() + "/" + code)));
                }
            }
            result.add(new AudienceLocation(place.value(), place.label(), offered));
        }
        return new AudienceOptionsView(result);
    }

    /**
     * What people read for each code of a picklist of the mapping object ({@code location} or {@code department}); a
     * code the list no longer has is read as itself.
     */
    Map<String, String> labels(String field) {
        Map<String, String> labels = new LinkedHashMap<>();
        metadata.object(Templates.DEPARTMENT_OBJECT).flatMap(object -> object.field(field))
                .filter(found -> found.configuration() instanceof PicklistConfiguration)
                .ifPresent(found -> ((PicklistConfiguration) found.configuration()).values()
                        .forEach(value -> labels.put(value.value(), value.label())));
        return labels;
    }

    /** The pairs that have a mapping record, as {@code location/department}. */
    private Set<String> configuredPairs() {
        Set<String> pairs = new HashSet<>();
        UUID after = null;
        int read = 0;
        while (read < MOST_MAPPINGS) {
            List<DataRecord> page = records.scan(Templates.DEPARTMENT_OBJECT, null, after, 200);
            for (DataRecord mapping : page) {
                Object place = mapping.fields().get(Templates.LOCATION);
                Object part = mapping.fields().get(Templates.DEPARTMENT);
                if (place instanceof String && part instanceof String) {
                    pairs.add(place + "/" + part);
                }
            }
            read += page.size();
            if (page.size() < 200) {
                break;
            }
            after = page.get(page.size() - 1).id();
        }
        return pairs;
    }

    private static UUID groupId(DataRecord mapping) {
        Object value = mapping.fields().get(Templates.GROUP_ID);
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException refused(int index, String words) {
        return ApiException.validation("audiences[" + index + "]", words);
    }
}
