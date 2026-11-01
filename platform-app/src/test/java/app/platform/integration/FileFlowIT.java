package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestFileServer;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 29A for file flows (ADR-0237, ADR-0240 to ADR-0243), against a real file server: a file in
 * the inbox is applied once and moved to done; the same file again is not applied twice; a file that is bad as a whole
 * is set aside and nothing of it is applied; a record sends one file under a name made from its delivery, written under
 * a temporary name and renamed so a reader never sees half of it; and an answer that was lost does not make a second
 * file. Rows, titles and file names are made up per test and must be nowhere in what an administrator reads.
 */
@FileFlowTest
class FileFlowIT extends FileSupport {

    private TestFileServer server;

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aFileInTheInboxIsAppliedOnceAndMovedToDoneAndNothingIsSentBack() throws Exception {
        Site site = site(server, true, true, "csv");
        String a = "title-" + unique();
        String b = "title-" + unique();
        String c = "title-" + unique();
        String d = "title-" + unique();
        String e = "title-" + unique();
        drop(server, "orders-" + unique() + ".csv", csv(row("1", a), row("2", b), row("3", c), row("4", d),
                row("5", e)));

        lookNow(site);
        await(site.linked(), "the file to be applied and moved", () -> fileCount(site, "filesDone") == 1);

        for (String title : List.of(a, b, c, d, e)) {
            assertThat(recordsWithTitle(site.admin(), title)).as(title).isEqualTo(1);
        }
        assertThat(server.visibleNames("/inbox")).as("the inbox is empty").isEmpty();
        assertThat(server.visibleNames("/processing")).isEmpty();
        assertThat(server.visibleNames("/error")).isEmpty();
        assertThat(server.visibleNames("/done")).hasSize(1).allSatisfy(name -> assertThat(name).contains("__orders-"));
        assertThat(ledger(site, "DONE")).isEqualTo(1);
        assertThat(fileCount(site, "filesSetAside")).isZero();
        // The records the file made are not sent back to the server they came from.
        Thread.sleep(1_500);
        assertThat(server.visibleNames("/outbox")).as("nothing is echoed").isEmpty();
    }

    @Test
    void aFileSeenTwiceIsAppliedOnce() throws Exception {
        Site site = site(server, true, false, "csv");
        String title = "title-" + unique();
        Instant modified = Instant.now().minusSeconds(120);
        String content = csv(row("9", title));
        server.put("/inbox", "same.csv", content, modified);
        lookNow(site);
        await(site.linked(), "the first file to be applied", () -> fileCount(site, "filesDone") == 1);

        server.put("/inbox", "same.csv", content, modified);
        lookNow(site);
        await(site.linked(), "the second file to leave the inbox", () -> server.visibleNames("/inbox").isEmpty());

        assertThat(recordsWithTitle(site.admin(), title)).as("one row, one record").isEqualTo(1);
        assertThat(server.visibleNames("/done")).as("both files are kept for a person").hasSize(2);
        assertThat(ledger(site, "DONE")).as("but it was applied once").isEqualTo(1);
    }

    @Test
    void aFileThatIsBadAsAWholeIsSetAsideAndNothingOfItIsApplied() throws Exception {
        Site site = site(server, true, false, "csv");
        String good = "title-" + unique();
        String neverApplied = "title-" + unique();
        List<String> names = new ArrayList<>();

        // no identifier column
        names.add("no-id-" + unique() + ".csv");
        server.put("/inbox", names.getLast(), "name,title\nx," + neverApplied + "\n", Instant.now().minusSeconds(90));
        // the second row has no identifier: the first, good, row must not be applied either
        names.add("half-" + unique() + ".csv");
        server.put("/inbox", names.getLast(), csv(row("1", neverApplied), ",title-x,note"),
                Instant.now().minusSeconds(80));
        // more rows than the platform processes in one file (the tests allow 50)
        names.add("long-" + unique() + ".csv");
        StringBuilder many = new StringBuilder("id,title,note\n");
        for (int i = 0; i < 60; i++) {
            many.append(row("m" + i, neverApplied)).append('\n');
        }
        server.put("/inbox", names.getLast(), many.toString(), Instant.now().minusSeconds(70));
        // not text
        names.add("binary-" + unique() + ".csv");
        Files.write(server.directory("/inbox").resolve(names.getLast()), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1});
        // a good file after the bad ones is not held up by them
        server.put("/inbox", "good-" + unique() + ".csv", csv(row("g1", good)), Instant.now().minusSeconds(60));

        lookNow(site);
        await(site.linked(), "every file to be handled", () -> fileCount(site, "filesSetAside") == 4
                && fileCount(site, "filesDone") == 1);

        assertThat(recordsWithTitle(site.admin(), neverApplied)).as("nothing of a bad file is applied").isZero();
        assertThat(recordsWithTitle(site.admin(), good)).isEqualTo(1);
        assertThat(server.visibleNames("/error")).hasSize(4);
        assertThat(server.visibleNames("/inbox")).isEmpty();
        assertThat(ledger(site, "SET_ASIDE")).isEqualTo(4);
        for (String name : names) {
            assertThat(server.visibleNames("/error")).anySatisfy(found -> assertThat(found).endsWith("__" + name));
        }
    }

    @Test
    void aFileThatIsNotOneTheConnectionPicksUpIsLeftWhereItIs() throws Exception {
        Site site = site(server, true, false, "csv");
        String title = "title-" + unique();
        List<String> left = List.of("notes.txt", ".hidden.csv", "with space.csv", "orders.csv.exe", "UPPER.CSV.bak");
        for (String name : left) {
            server.put("/inbox", name, csv(row("1", title)), Instant.now().minusSeconds(60));
        }
        server.put("/inbox", "orders.CSV", csv(row("2", title)), Instant.now().minusSeconds(60));

        lookNow(site);
        await(site.linked(), "the one good file", () -> fileCount(site, "filesDone") == 1);

        assertThat(server.names("/inbox")).containsExactlyInAnyOrderElementsOf(left);
        assertThat(recordsWithTitle(site.admin(), title)).isEqualTo(1);
    }

    @Test
    void aJsonLinesFileIsAppliedLikeACsvFile() throws Exception {
        Site site = site(server, true, false, "jsonl");
        String one = "title-" + unique();
        String two = "title-" + unique();
        drop(server, "lines-" + unique() + ".jsonl", "{\"id\":\"1\",\"title\":\"" + one + "\",\"note\":\"n\"}\n\n"
                + "{\"id\":\"2\",\"title\":\"" + two + "\"}\n");

        lookNow(site);
        await(site.linked(), "the file to be applied", () -> fileCount(site, "filesDone") == 1);

        assertThat(recordsWithTitle(site.admin(), one)).isEqualTo(1);
        assertThat(recordsWithTitle(site.admin(), two)).isEqualTo(1);
    }

    @Test
    void aRecordSendsOneFileNamedFromItsDeliveryAndNeverShowsAHalfFile() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String title = "title-" + unique();

        String record = created(admin, title);
        await(site.linked(), "the file to be written", () -> server.visibleNames("/outbox").size() == 1);
        await(site.linked(), "the delivery to be done", () -> count(admin, site.connection(), "DONE") == 1);

        String name = server.visibleNames("/outbox").getFirst();
        String delivery = JsonPath.read(deliveries(admin, site.connection(), "DONE").body(), "$.data[0].id");
        assertThat(name).isEqualTo("d-" + delivery + "-g1.csv");
        assertThat(server.read("/outbox", name)).isEqualTo("id,operation,title\n" + record + ",create," + title
                + "\n");
        assertThat(server.names("/outbox")).as("no temporary file is left behind").isEqualTo(List.of(name));
        assertThat(JsonPath.<Integer>read(deliveries(admin, site.connection(), "DONE").body(), "$.data[0].attempts"))
                .isEqualTo(1);
    }

    @Test
    void aChangeOfARecordSendsAnotherFileAndTheLogHoldsNoNameOrValue() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String title = "title-" + unique();
        String record = created(admin, title);
        await(site.linked(), "the first file", () -> count(admin, site.connection(), "DONE") == 1);

        String changed = "changed-" + unique();
        var changedBody = admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + record,
                "{\"version\":0,\"fields\":{\"" + TITLE_FIELD + "\":\"" + changed + "\"}}");
        assertThat(changedBody.status()).as(changedBody.body()).isEqualTo(200);
        await(site.linked(), "the second file", () -> count(admin, site.connection(), "DONE") == 2);

        List<String> contents = server.contents("/outbox");
        assertThat(contents).hasSize(2);
        assertThat(contents).anySatisfy(text -> assertThat(text).contains(",create,").contains(title));
        assertThat(contents).anySatisfy(text -> assertThat(text).contains(",update,").contains(changed));
        String everything = deliveries(admin, site.connection()).body() + health(admin, site.connection()).body();
        assertThat(everything).doesNotContain(title).doesNotContain(changed).doesNotContain(".csv")
                .doesNotContain("-g1");
    }

    @Test
    void anAnswerThatWasLostDoesNotMakeASecondFile() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String title = "title-" + unique();

        // The server is down when the record is made, so the send waits; its name is known from the queue.
        server.down();
        String record = created(admin, title);
        await(site.linked(), "the delivery to wait", () -> count(admin, site.connection(), "PENDING") == 1);
        String listed = deliveries(admin, site.connection()).body();
        String delivery = JsonPath.read(listed, "$.data[0].id");
        int generation = JsonPath.read(listed, "$.data[0].generation");
        // What an earlier attempt whose answer was lost left behind: the file, complete, under the name of the send.
        String expected = "id,operation,title\n" + record + ",create," + title + "\n";
        Path placed = server.directory("/outbox").resolve("d-" + delivery + "-g" + generation + ".csv");
        Files.writeString(placed, expected);

        server.up();
        await(site.linked(), "the delivery to be done", () -> count(admin, site.connection(), "DONE") == 1);

        assertThat(server.names("/outbox")).as("one file, the one that was there").containsExactly(placed.getFileName()
                .toString());
        assertThat(server.writeCount()).as("and nothing was written again").isZero();
    }

    @Test
    void aConnectionCutInTheMiddleOfAFileLeavesNoFileAndIsTriedAgain() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String title = "title-" + unique();

        server.dropWhileWriting(true);
        String record = created(admin, title);
        await(site.linked(), "the first attempt to fail", () -> server.writeCount() >= 1);
        assertThat(server.visibleNames("/outbox")).as("a reader never meets half a file").isEmpty();

        server.dropWhileWriting(false);
        await(site.linked(), "the delivery to be done", () -> count(admin, site.connection(), "DONE") == 1);

        assertThat(server.visibleNames("/outbox")).hasSize(1);
        assertThat(server.read("/outbox", server.visibleNames("/outbox").getFirst())).contains(record).contains(title);
    }
}
