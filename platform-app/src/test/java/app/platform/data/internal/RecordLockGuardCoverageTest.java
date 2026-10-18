package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rule of ADR-0148 held by a test (Sprint 24): every class that changes a stored record asks the lock guard, so a
 * new
 * path cannot skip the lock. It reads the sources of the module and fails when a class outside a short, reasoned list
 * calls a writing method of the storage, in the style of {@code OnlyTheStorageNamesTheTableTest}.
 *
 * <p>The list is the whole truth about which classes may write a record and why each is, or need not be, guarded. A new
 * entry needs a reason that a reviewer accepts.
 */
class RecordLockGuardCoverageTest {

    private static final Path DATA = Path.of("src/main/java/app/platform/data");
    private static final Pattern WRITE = Pattern.compile(
            "\\bstorage\\.(update|softDelete|restore|purge|changeOwner|updateCalculated|purgeDeletedOlderThan)\\(");

    /** A class that writes a stored record and asks the guard (directly or through the authorization stage). */
    private static final Map<String, String> GUARDED = Map.of(
            "DefaultRecords.java", "update, delete, restore, purge: the record permission stage asks the guard",
            "RecordRemoval.java", "the details a removal takes and the pointers it clears are guarded before it writes",
            "DefaultBackgroundWrites.java", "a workflow's write asks the guard for the platform",
            "RecordSharing.java", "the owner is a field of the record: it goes through RecordAuthorization.forEdit");

    /** A class that writes a stored record and need not ask the guard, with the reason. */
    private static final Map<String, String> EXEMPT = Map.of(
            "FormulaResults.java", "a stored formula result is a calculated copy, not a change (ADR-0114)",
            "RecycleBinRetention.java", "removes only records that were deleted, and a record under approval cannot be",
            "JsonbRecordStorage.java", "the storage itself");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(DATA)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static boolean writes(Path path) {
        try {
            Matcher matcher = WRITE.matcher(Files.readString(path));
            return matcher.find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyClassThatWritesARecordIsOnTheReviewedList() throws IOException {
        List<String> writers = sources().stream().filter(RecordLockGuardCoverageTest::writes)
                .map(path -> path.getFileName().toString()).toList();

        assertThat(writers).as("classes that call a writing method of the storage")
                .allSatisfy(name -> assertThat(GUARDED.containsKey(name) || EXEMPT.containsKey(name))
                        .as(name + " writes a record: it must ask RecordLockGuard or be listed with a reason")
                        .isTrue());
    }

    @Test
    void everyGuardedWriterReallyAsksTheGuardOrTheAuthorizationThatDoes() throws IOException {
        for (Path path : sources()) {
            String name = path.getFileName().toString();
            if (!GUARDED.containsKey(name)) {
                continue;
            }
            String text = Files.readString(path);
            assertThat(text.contains("lockGuard.") || text.contains("authorization.forEdit("))
                    .as(name + " is listed as guarded: " + GUARDED.get(name)).isTrue();
        }
    }

    @Test
    void theListNamesOnlyClassesThatExistAndStillWrite() throws IOException {
        List<String> existing = sources().stream().map(path -> path.getFileName().toString()).toList();

        assertThat(existing).containsAll(GUARDED.keySet()).containsAll(EXEMPT.keySet());
    }

    @Test
    void onlyTheGuardTakesTheRowLockOfARecord() throws IOException {
        List<String> lockers = sources().stream().filter(path -> {
            try {
                return Files.readString(path).contains("storage.lockRows(");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).map(path -> path.getFileName().toString()).toList();

        assertThat(lockers).containsExactlyInAnyOrder("RecordLockGuard.java", "DefaultBackgroundRecords.java");
    }
}
