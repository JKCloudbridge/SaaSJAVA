package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The ability catalogue (ADR-0039): stable keys, the shape the database accepts, and the key the database knows. */
class AbilityTest {

    private static final String KEY_SHAPE = "[a-z][a-z0-9.-]{0,59}";

    @Test
    void everyKeyIsUniqueDocumentedAndHasTheShapeTheDatabaseAccepts() {
        List<String> keys = Arrays.stream(Ability.values()).map(Ability::key).toList();

        assertThat(keys).doesNotHaveDuplicates().allMatch(key -> key.matches(KEY_SHAPE));
        assertThat(Arrays.stream(Ability.values()).map(Ability::title)).allMatch(title -> !title.isBlank());
        assertThat(Arrays.stream(Ability.values()).map(Ability::description)).allMatch(text -> !text.isBlank());
    }

    @Test
    void aKeyFindsItsAbilityAndAnUnknownKeyIsIgnoredNotAnError() {
        for (Ability ability : Ability.values()) {
            assertThat(Ability.fromKey(ability.key())).contains(ability);
            assertThat(Ability.fromKey("  " + ability.key().toUpperCase(java.util.Locale.ROOT) + " "))
                    .contains(ability);
        }
        assertThat(Ability.fromKey("no.such.ability")).isEmpty();
        assertThat(Ability.fromKey(null)).isEmpty();
        assertThat(Ability.knownAmong(List.of("members.view", "dropped.in.a.later.release", "access.manage")))
                .containsExactlyInAnyOrder(Ability.MEMBERS_VIEW, Ability.ACCESS_MANAGE);
    }

    @Test
    void theKeysAreReturnedSortedAndWithoutDuplicates() {
        assertThat(Ability.keysOf(Set.of(Ability.SESSIONS_MANAGE, Ability.ACCESS_MANAGE, Ability.MEMBERS_VIEW)))
                .containsExactly("access.manage", "members.view", "sessions.manage");
    }

    /** The database guard names the ability by its key; the two must never drift apart (V022, ADR-0044). */
    @Test
    void theKeyThatTheDatabaseGuardKnowsIsTheKeyOfAccessManage() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V022__last_access_manager_guard.sql");
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        long occurrences = sql.lines().filter(line -> !line.strip().startsWith("--"))
                .filter(line -> line.contains("'" + Ability.ACCESS_MANAGE.key() + "'")).count();

        assertThat(occurrences).as("the guard counts holders of exactly this key").isGreaterThanOrEqualTo(2);
        Set<String> otherKeysInSql = Arrays.stream(Ability.values()).filter(a -> a != Ability.ACCESS_MANAGE)
                .map(Ability::key).filter(key -> sql.contains("'" + key + "'")).collect(Collectors.toSet());
        assertThat(otherKeysInSql).as("the guard protects one ability only").isEmpty();
    }
}
