package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestFileServer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two real instances of the application against one database and one file server (Sprint 29A, ADR-0242): both look for
 * due polls, the lease of a poll lets exactly one of them look at a connection at a time, and the rename that takes a
 * file out of the inbox lets exactly one of them have it. Whichever instance does the work, every row is written once,
 * every file is processed once and the ledger says so.
 */
@FileFlowTest
class FilePollMultiInstanceIT extends FileSupport {

    private static final String[] PROPERTIES = {
        "platform.outbox.enabled=true",
        "platform.integration.reliability.poll-interval=100ms",
        "platform.integration.reliability.retry.initial-delay=300ms",
        "platform.integration.reliability.retry.max-delay=2s",
        "platform.integration.reliability.queue.idle-recheck=1s",
        "platform.integration.files.min-interval=5s",
        "platform.integration.files.batch-rows=2",
        "platform.integration.files.lease=60s",
        "platform.integration.files.deadline=40s",
        "platform.integration.files.max-rows=50"};

    private TestFileServer server;
    private TestApplication second;

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
        second = TestApplication.start(PROPERTIES);
    }

    @AfterEach
    void stop() {
        second.close();
        server.close();
    }

    @Test
    void twoInstancesApplyEveryRowOfEveryFileOnceAndProcessEveryFileOnce() throws Exception {
        Site site = site(server, true, false, "csv");
        List<String> titles = new ArrayList<>();
        int files = 6;
        for (int f = 0; f < files; f++) {
            StringBuilder text = new StringBuilder("id,title,note\n");
            for (int r = 0; r < 3; r++) {
                String title = "title-" + UUID.randomUUID();
                titles.add(title);
                text.append(row("f" + f + "r" + r, title)).append('\n');
            }
            drop(server, "file-" + f + "-" + UUID.randomUUID().toString().substring(0, 6) + ".csv", text.toString());
        }

        lookNow(site);
        await(site.linked(), "every file to be processed", () -> fileCount(site, "filesDone") == files);
        // Both instances keep looking; nothing more is found and nothing is done again.
        lookNow(site);
        lookNow(site);
        Thread.sleep(3_000);

        for (String title : titles) {
            assertThat(recordsWithTitle(site.admin(), title)).as(title).isEqualTo(1);
        }
        assertThat(ledger(site, "DONE")).isEqualTo(files);
        assertThat(ledger(site, "CLAIMED") + ledger(site, "CLAIMING") + ledger(site, "APPLIED")
                + ledger(site, "SET_ASIDE")).isZero();
        assertThat(server.visibleNames("/done")).hasSize(files);
        assertThat(server.visibleNames("/inbox")).isEmpty();
        assertThat(server.visibleNames("/processing")).isEmpty();
        assertThat(fileCount(site, "filesSetAside")).isZero();
    }
}
