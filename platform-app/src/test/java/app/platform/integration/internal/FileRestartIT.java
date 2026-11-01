package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import app.platform.integration.FileFlowTest;
import app.platform.integration.FileSupport;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestFileServer;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A look that is cut off at any point repeats no row and loses none (Sprint 29A, ADR-0241, ADR-0242). The receiver of
 * the rows is watched with a spy that behaves as the real one, except where the test makes it fail, so the test can say
 * exactly which rows were written, in which look, and how often.
 *
 * <ul>
 *   <li>cut in the middle of the batches: the first batch is committed with its progress, the second is not, and the
 * next
 *       look resumes at the third row and writes the three rows that are left, once;
 *   <li>cut after the last row but before the file is moved: the next look moves the file and writes no row at all.
 * </ul>
 */
@FileFlowTest
class FileRestartIT extends FileSupport {

    @MockitoSpyBean
    private InboundService inbound;

    private TestFileServer server;

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
        reset(inbound);
    }

    @AfterEach
    void stop() {
        reset(inbound);
        server.close();
    }

    @Test
    void aLookCutOffBetweenTwoBatchesIsResumedAtTheFirstRowThatWasNotWritten() throws Exception {
        Site site = site(server, true, false, "csv");
        List<String> titles = new ArrayList<>();
        StringBuilder text = new StringBuilder("id,title,note\n");
        for (int i = 0; i < 5; i++) {
            String title = "title-" + UUID.randomUUID();
            titles.add(title);
            text.append(row("r" + i, title)).append('\n');
        }
        drop(server, "five.csv", text.toString());

        // From the third row on every row throws: the batch it belongs to rolls back, and so does the look, until the
        // test lets the rows through again.
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() >= 3) {
                throw new IllegalStateException("cut off");
            }
            return invocation.callRealMethod();
        }).when(inbound).applyEntity(any(), any(), any(), any(), any());
        lookNow(site);
        await(site.linked(), "the look to be cut off", () -> calls.get() >= 3);
        await(site.linked(), "the lease to be released", () -> pollIsFree(site));

        assertThat(ledger(site, "CLAIMED")).as("the file is held, not lost").isEqualTo(1);
        assertThat(server.visibleNames("/processing")).hasSize(1);
        assertThat(server.visibleNames("/inbox")).isEmpty();
        assertThat(titles.stream().filter(title -> recordsWithTitle(site.admin(), title) == 1).count())
                .as("the first batch was committed with its progress, the second was not").isEqualTo(2);
        assertThat(progress(site)).containsExactly(5, 2, 2);

        // The next look writes the three rows that are left, once each, and moves the file.
        reset(inbound);
        AtomicInteger resumed = new AtomicInteger();
        doAnswer(invocation -> {
            resumed.incrementAndGet();
            return invocation.callRealMethod();
        }).when(inbound).applyEntity(any(), any(), any(), any(), any());
        lookNow(site);
        await(site.linked(), "the file to be finished", () -> fileCount(site, "filesDone") == 1);

        assertThat(resumed.get()).as("no row is written twice").isEqualTo(3);
        for (String title : titles) {
            assertThat(recordsWithTitle(site.admin(), title)).as(title).isEqualTo(1);
        }
        assertThat(progress(site)).containsExactly(5, 5, 5);
        assertThat(server.visibleNames("/done")).hasSize(1);
        assertThat(server.visibleNames("/processing")).isEmpty();
    }

    @Test
    void aLookCutOffAfterTheLastRowButBeforeTheMoveMovesTheFileAndWritesNothingMore() throws Exception {
        Site site = site(server, true, false, "csv");
        List<String> titles = new ArrayList<>();
        StringBuilder text = new StringBuilder("id,title,note\n");
        for (int i = 0; i < 3; i++) {
            String title = "title-" + UUID.randomUUID();
            titles.add(title);
            text.append(row("m" + i, title)).append('\n');
        }
        drop(server, "three.csv", text.toString());

        // After the last row the server stops letting the platform move files: the rows are in, the file is not moved.
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Object outcome = invocation.callRealMethod();
            if (calls.incrementAndGet() == 3) {
                server.denyWrites(true);
            }
            return outcome;
        }).when(inbound).applyEntity(any(), any(), any(), any(), any());
        lookNow(site);
        await(site.linked(), "every row to be written", () -> calls.get() == 3);
        await(site.linked(), "the lease to be released", () -> pollIsFree(site));

        assertThat(ledger(site, "APPLIED")).as("everything is written and the ledger says so").isEqualTo(1);
        assertThat(server.visibleNames("/processing")).hasSize(1);
        assertThat(server.visibleNames("/done")).isEmpty();

        reset(inbound);
        AtomicInteger again = new AtomicInteger();
        doAnswer(invocation -> {
            again.incrementAndGet();
            return invocation.callRealMethod();
        }).when(inbound).applyEntity(any(), any(), any(), any(), any());
        server.denyWrites(false);
        lookNow(site);
        await(site.linked(), "the file to be moved", () -> fileCount(site, "filesDone") == 1);

        assertThat(again.get()).as("not one row is written again").isZero();
        for (String title : titles) {
            assertThat(recordsWithTitle(site.admin(), title)).isEqualTo(1);
        }
        assertThat(server.visibleNames("/done")).hasSize(1);
        assertThat(server.visibleNames("/processing")).isEmpty();
    }

    /** Whether the poll of the connection has released its lease (a look that ended, by whatever way). */
    private boolean pollIsFree(Site site) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, site.organization().id());
            try (PreparedStatement free = owner.prepareStatement("select count(*) from integration_poll where "
                    + "tenant_id = ? and locked_until is null")) {
                free.setObject(1, site.organization().id().value());
                try (ResultSet found = free.executeQuery()) {
                    found.next();
                    return found.getInt(1) == 1;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The rows of the file, the rows done and the rows applied, as the ledger holds them. */
    private int[] progress(Site site) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, site.organization().id());
            try (PreparedStatement read = owner.prepareStatement("select coalesce(rows_total, 0), rows_done, "
                    + "rows_applied from integration_file where tenant_id = ?")) {
                read.setObject(1, site.organization().id().value());
                try (ResultSet found = read.executeQuery()) {
                    assertThat(found.next()).isTrue();
                    return new int[] {found.getInt(1), found.getInt(2), found.getInt(3)};
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
