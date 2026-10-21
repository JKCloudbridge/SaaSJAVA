package app.platform.data.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the database's list of retained objects in step with the definition files (ADR-0174 section 7, ADR-0181).
 *
 * <p>An object that declares {@code retainRecords} has its name written to {@code retained_object} when the application
 * starts, and from then on the database itself refuses to delete or soft-delete a record of it (triggers on {@code
 * object_record}), whatever code or person tries. The list only grows: the table is insert-only, which is the
 * database's form of "never delete or rename a released definition". A name that is in the table and no longer
 * declared is reported once at start and stays protected, because a rollback to an older release must not open the
 * records of an object that a newer release kept.
 *
 * <p>The code refuses first ({@code RecordGate}); this is the layer under it. Several instances starting together are
 * harmless: the insert ignores a name that exists. A failure stops the start, because running without the list would
 * silently remove a protection the definitions promise.
 */
@Component
class RetainedObjects implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RetainedObjects.class);

    private static final String INSERT = "insert into retained_object (object_api_name, created_by, updated_by) "
            + "values (:name, :actor, :actor) on conflict (object_api_name) where deleted_at is null do nothing";
    private static final String NAMES = "select object_api_name from retained_object where deleted_at is null";

    private final PlatformObjects objects;
    private final JdbcClient jdbc;

    RetainedObjects(PlatformObjects objects, JdbcClient jdbc) {
        this.objects = objects;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        sync();
    }

    /** Writes the names that are declared and are not yet in the table; returns how many it wrote. */
    int sync() {
        List<String> declared = objects.all().stream().filter(ObjectDefinition::retainRecords)
                .map(ObjectDefinition::apiName).sorted().toList();
        int written = 0;
        for (String name : declared) {
            written += jdbc.sql(INSERT).param("name", name).param("actor", ActorId.SYSTEM.value()).update();
        }
        Set<String> stored = jdbc.sql(NAMES).query(String.class).set();
        Set<String> forgotten = stored.stream().filter(name -> !declared.contains(name)).collect(Collectors.toSet());
        if (!forgotten.isEmpty()) {
            LOG.warn("{} retained objects are no longer declared by this release; their records stay protected",
                    forgotten.size());
        }
        if (written > 0) {
            LOG.info("Recorded {} retained objects", written);
        }
        return written;
    }
}
