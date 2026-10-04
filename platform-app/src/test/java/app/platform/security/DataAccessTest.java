package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The permission value of a container or a member: implications, union, covering (ADR-0049, ADR-0050). */
class DataAccessTest {

    private static DataAccess objects(String key, ObjectAction... actions) {
        return DataAccess.of(Map.of(key, EnumSet.copyOf(Set.of(actions))), Map.of());
    }

    private static DataAccess fields(String key, FieldAction... actions) {
        return DataAccess.of(Map.of(), Map.of(key, EnumSet.copyOf(Set.of(actions))));
    }

    @Test
    void nothingAllowsNothing() {
        assertThat(DataAccess.none().isEmpty()).isTrue();
        assertThat(DataAccess.none().allows("object-a", ObjectAction.READ)).isFalse();
        assertThat(DataAccess.none().allowsField("object-a.field-a", FieldAction.READ)).isFalse();
    }

    @Test
    void fullAccessAllowsEverythingOnAnyObjectAndField() {
        DataAccess everything = DataAccess.fullAccess();

        for (ObjectAction action : ObjectAction.values()) {
            assertThat(everything.allows("object-z", action)).as(action.key()).isTrue();
        }
        for (FieldAction action : FieldAction.values()) {
            assertThat(everything.allowsField("object-z.field-z", action)).as(action.key()).isTrue();
        }
    }

    @Test
    void createUpdateDeleteAndViewAllEachImplyRead() {
        for (ObjectAction action : List.of(ObjectAction.CREATE, ObjectAction.UPDATE, ObjectAction.DELETE,
                ObjectAction.VIEW_ALL)) {
            DataAccess held = objects("object-a", action);

            assertThat(held.allows("object-a", ObjectAction.READ)).as(action.key() + " implies read").isTrue();
            assertThat(held.allows("object-a", action)).isTrue();
        }
    }

    @Test
    void anActionDoesNotImplyTheOthers() {
        DataAccess update = objects("object-a", ObjectAction.UPDATE);

        assertThat(update.allows("object-a", ObjectAction.CREATE)).isFalse();
        assertThat(update.allows("object-a", ObjectAction.DELETE)).isFalse();
        assertThat(update.allows("object-a", ObjectAction.VIEW_ALL)).isFalse();
        assertThat(update.allows("object-a", ObjectAction.MODIFY_ALL)).isFalse();
        assertThat(update.allows("object-b", ObjectAction.READ)).as("another object").isFalse();
    }

    @Test
    void modifyAllImpliesTheOtherFiveAndNothingMore() {
        DataAccess held = objects("object-a", ObjectAction.MODIFY_ALL);

        for (ObjectAction action : ObjectAction.values()) {
            assertThat(held.allows("object-a", action)).as(action.key()).isTrue();
        }
        assertThat(held.closed().objects().get("object-a")).containsExactlyInAnyOrder(ObjectAction.values());
    }

    @Test
    void editImpliesReadForAField() {
        DataAccess held = fields("object-a.field-a", FieldAction.EDIT);

        assertThat(held.allowsField("object-a.field-a", FieldAction.READ)).isTrue();
        assertThat(held.allowsField("object-a.field-b", FieldAction.READ)).as("another field").isFalse();
        assertThat(fields("object-a.field-a", FieldAction.READ).allowsField("object-a.field-a", FieldAction.EDIT))
                .as("read does not imply edit").isFalse();
    }

    @Test
    void aFieldPermissionDoesNotGiveTheObject() {
        DataAccess held = fields("object-a.field-a", FieldAction.EDIT);

        assertThat(held.allows("object-a", ObjectAction.READ)).isFalse();
    }

    @Test
    void unionKeepsEverythingEitherSideAllows() {
        DataAccess left = objects("object-a", ObjectAction.READ);
        DataAccess right = objects("object-a", ObjectAction.DELETE).union(objects("object-b", ObjectAction.CREATE));

        DataAccess both = left.union(right);

        assertThat(both.allows("object-a", ObjectAction.READ)).isTrue();
        assertThat(both.allows("object-a", ObjectAction.DELETE)).isTrue();
        assertThat(both.allows("object-b", ObjectAction.CREATE)).isTrue();
        assertThat(both.allows("object-b", ObjectAction.UPDATE)).isFalse();
        assertThat(left.union(right)).isEqualTo(right.union(left));
        assertThat(left.union(DataAccess.fullAccess()).everything()).isTrue();
    }

    @Test
    void theValueIsACopyThatCannotBeChangedFromOutside() {
        Set<ObjectAction> actions = EnumSet.of(ObjectAction.READ);
        Map<String, Set<ObjectAction>> source = new java.util.HashMap<>(Map.of("object-a", actions));
        DataAccess held = DataAccess.of(source, Map.of());

        source.put("object-b", EnumSet.of(ObjectAction.READ));
        actions.add(ObjectAction.DELETE);

        assertThat(held.objectKeys()).containsExactly("object-a");
        assertThat(held.allows("object-a", ObjectAction.DELETE)).isFalse();
    }

    @Test
    void coveringMeansEverythingTheOtherAllowsIsAllowedHere() {
        DataAccess updater = objects("object-a", ObjectAction.UPDATE);
        DataAccess reader = objects("object-a", ObjectAction.READ);

        assertThat(updater.covers(reader)).as("update includes read").isTrue();
        assertThat(reader.covers(updater)).isFalse();
        assertThat(reader.covers(DataAccess.none())).isTrue();
        assertThat(DataAccess.none().covers(reader)).isFalse();
        assertThat(DataAccess.fullAccess().covers(updater)).isTrue();
        assertThat(updater.covers(DataAccess.fullAccess())).isFalse();
        assertThat(fields("object-a.field-a", FieldAction.EDIT).covers(fields("object-a.field-a", FieldAction.READ)))
                .isTrue();
        assertThat(fields("object-a.field-a", FieldAction.READ).covers(fields("object-a.field-b", FieldAction.READ)))
                .isFalse();
    }
}
