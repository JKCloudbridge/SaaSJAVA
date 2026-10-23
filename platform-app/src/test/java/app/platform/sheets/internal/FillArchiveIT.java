package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.FillSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The archive of old fill-ins, end to end (Functional track FF2, ADR-0193): an approved fill-in older than the age
 * has its
 * cells moved to the object store and read back identical on demand, the row stays with its hash, nothing is changed
 * afterwards by anyone, two runs at once move it once, and the database refuses every other way to do it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false",
        "platform.sheets.archive.enabled=false"})
class FillArchiveIT extends FillSupport {

    @Autowired
    private FillArchiveJob job;

    /** An approved fill-in whose review ended long ago. */
    private String oldApprovedFill(World world) throws SQLException {
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());
        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);
        assertThat(fillStatus(world.approver().browser(), fill)).isEqualTo("APPROVED");
        age(world, fill);
        return fill;
    }

    private void age(World world, String fill) throws SQLException {
        TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(connection,
                "update sheet_fill set decided_at = now() - interval '400 days', version = version + 1 "
                        + "where fill_id = ?", UUID.fromString(fill)));
    }

    private long archivedRows(World world, String fill) {
        return scalar(world.setting().organization(), "select count(*) from sheet_fill where fill_id = ? "
                + "and archived_at is not null and cells = cast('{}' as jsonb)", UUID.fromString(fill));
    }

    @Test
    void anOldApprovedFillInIsMovedAndReadBackIdenticalAndReadOnly() throws Exception {
        World world = world();
        String fill = oldApprovedFill(world);
        Response before = fillView(world.approver().browser(), fill);

        job.runOnce();

        assertThat(archivedRows(world, fill)).as("the cells left the row").isEqualTo(1);
        Response after = fillView(world.approver().browser(), fill);
        assertThat(after.status()).as(after.body()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(after.body(), "$.data.cells")).isEqualTo(
                JsonPath.<Object>read(before.body(), "$.data.cells"));
        assertThat(JsonPath.<String>read(after.body(), "$.data.fill.cellsHash")).isEqualTo(
                JsonPath.<String>read(before.body(), "$.data.fill.cellsHash"));
        assertThat(JsonPath.<String>read(after.body(), "$.data.fill.archivedAt")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(after.body(), "$.data.canEdit")).as("read-only").isFalse();
        Response refused = saveCells(world.approver().browser(), fill, fillVersion(after), complete());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(scalar(world.setting().organization(), "select count(*) from sheet_fill where fill_id = ?",
                UUID.fromString(fill))).as("never deleted").isEqualTo(1);
    }

    @Test
    void aFillInThatIsNotOldOrNotApprovedStaysWhereItIs() throws Exception {
        World world = world();
        String young = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), young, complete());
        String sentFill = started(world.colleague().browser(), world.template());
        saved(world.colleague().browser(), sentFill, complete());
        sent(world.colleague().browser(), sentFill, world.process());
        age(world, sentFill);

        job.runOnce();

        assertThat(archivedRows(world, young)).isZero();
        assertThat(archivedRows(world, sentFill)).as("sent, not approved").isZero();
    }

    @Test
    void twoRunsAtOnceMoveItOnce() throws Exception {
        World world = world();
        String fill = oldApprovedFill(world);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = job::runOnce;
            Future<Integer> first = pool.submit(run);
            Future<Integer> second = pool.submit(run);

            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(archivedRows(world, fill)).isEqualTo(1);
        assertThat(job.runOnce()).as("nothing left to move").isZero();
    }

    @Test
    void theDatabaseRefusesAnArchiveMoveThatIsNotTheLifecycle() throws Exception {
        World world = world();
        String fill = oldApprovedFill(world);
        String move = "update sheet_fill set cells = cast('{}' as jsonb), archived_at = now(), archive_key = 'k', "
                + "archive_bytes = 1, version = version + 1 where fill_id = ?";

        // without the window that only the archive opens
        SQLException refused = null;
        try {
            TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                    connection, move, UUID.fromString(fill)));
        } catch (SQLException e) {
            refused = e;
        }

        assertThat((Object) refused).isNotNull();
        assertThat(refused.getSQLState()).isEqualTo("23514");
        assertThat(archivedRows(world, fill)).isZero();
    }
}
