package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestStorage;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The files of a record (ADR-0120, ADR-0121): stored in the object store under a key of the organization, handed out
 * only to whoever may read the record, removed with their record, refused when they are too large, too many or the
 * wrong kind, and answered with a clean 503 when the store is down.
 */
@PlatformIntegrationTest
class RecordFilesIT extends RecordSecuritySupport {

    @Autowired
    private FileCleanup cleanup;

    private static String files(String record) {
        return ITEM_RECORDS + "/" + record + "/files";
    }

    private static BytesResponse upload(TestBrowser browser, String record, String name, String type, byte[] bytes) {
        return browser.bytes("POST", files(record) + "?name=" + java.net.URLEncoder.encode(name,
                StandardCharsets.UTF_8), bytes, "Content-Type", type);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        // Never starts like a program or a page: those are refused by their first bytes.
        bytes[0] = 'P';
        bytes[1] = 'K';
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String idOf(BytesResponse response) {
        return JsonPath.read(response.text(), "$.data.id");
    }

    @Test
    void aFileIsStoredWithItsSizeAndChecksumAndComesBackByteForByte() throws Exception {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "with a file");
        byte[] bytes = random(90_000);

        BytesResponse stored = upload(owner.browser(), record, "contract v1.zip", "application/zip", bytes);
        BytesResponse back = owner.browser().bytes("GET", files(record) + "/" + idOf(stored) + "/content", null);

        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
        assertThat(JsonPath.<Number>read(stored.text(), "$.data.size").longValue()).isEqualTo(bytes.length);
        assertThat(JsonPath.<String>read(stored.text(), "$.data.sha256")).isEqualTo(sha256(bytes));
        assertThat(JsonPath.<String>read(stored.text(), "$.data.name")).isEqualTo("contract v1.zip");
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.body()).isEqualTo(bytes);
        assertThat(back.header("Content-Disposition")).hasValueSatisfying(value ->
                assertThat(value).startsWith("attachment"));
        assertThat(back.header("X-Content-Type-Options")).contains("nosniff");
        assertThat(back.header("Cache-Control")).contains("no-store");
    }

    @Test
    void theBytesLieUnderAKeyOfTheOrganizationThatHoldsNoNameAPersonTyped() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "with a file");

        BytesResponse stored = upload(owner.browser(), record, "secret plans.zip", "application/zip", random(1000));

        List<String> keys = TestStorage.keys("files/" + items.organization().id().value() + "/");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).doesNotContain("secret").doesNotContain("plans").endsWith(idOf(stored));
        assertThat(TestStorage.bytes(keys.get(0))).hasSize(1000);
    }

    @Test
    void theListShowsTheFilesOfTheRecordInTheOrderTheyWereStored() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "with files");
        upload(owner.browser(), record, "one.zip", "application/zip", random(100));
        upload(owner.browser(), record, "two.zip", "application/zip", random(200));

        String list = owner.browser().get(files(record)).body();

        assertThat(JsonPath.<List<String>>read(list, "$.data[*].name")).containsExactly("one.zip", "two.zip");
    }

    // ---- who may ----

    @Test
    void aPersonWhoCannotReadTheRecordGetsTheAnswerOfARecordThatDoesNotExist() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person outsider = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "private");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));

        BytesResponse list = outsider.browser().bytes("GET", files(record), null);
        BytesResponse read = outsider.browser().bytes("GET", files(record) + "/" + file + "/content", null);
        BytesResponse put = upload(outsider.browser(), record, "b.zip", "application/zip", random(100));
        BytesResponse gone = outsider.browser().bytes("DELETE", files(record) + "/" + file, null);
        BytesResponse nothing = outsider.browser().bytes("GET", files(missing()), null);

        for (BytesResponse answer : List.of(list, read, put, gone)) {
            assertThat(answer.status()).as(answer.text()).isEqualTo(404);
        }
        assertThat(JsonPath.<String>read(list.text(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(nothing.text(), "$.error.message"));
        assertThat(owner.browser().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(200);
    }

    @Test
    void aPersonWhoMayReadButNotChangeTheRecordDownloadsButCannotUploadOrDelete() {
        Items items = items();
        share(items.admin(), "PUBLIC_READ", true);
        Person owner = worker(items.organization(), items.admin());
        Person reader = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "public");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));

        assertThat(reader.browser().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(200);
        assertThat(upload(reader.browser(), record, "b.zip", "application/zip", random(100)).status())
                .isEqualTo(403);
        assertThat(reader.browser().bytes("DELETE", files(record) + "/" + file, null).status()).isEqualTo(403);
    }

    @Test
    void aFileOfAnotherRecordIsNotFoundThroughThisOne() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String first = newItem(owner.browser(), "first");
        String second = newItem(owner.browser(), "second");
        String file = idOf(upload(owner.browser(), first, "a.zip", "application/zip", random(100)));

        assertThat(owner.browser().bytes("GET", files(second) + "/" + file + "/content", null).status())
                .isEqualTo(404);
        assertThat(owner.browser().bytes("DELETE", files(second) + "/" + file, null).status()).isEqualTo(404);
    }

    @Test
    void anotherOrganizationNeverReadsOrListsMyFiles() {
        Items mine = items();
        Items theirs = items();
        Person owner = worker(mine.organization(), mine.admin());
        String record = newItem(owner.browser(), "mine");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));

        assertThat(theirs.admin().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(404);
        assertThat(theirs.admin().bytes("GET", files(record), null).status()).isEqualTo(404);
    }

    @Test
    void anUnauthenticatedCallerAndAMissingForgeryHeaderAreRefused() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        TestBrowser nobody = new TestBrowser(port, items.organization().host());

        assertThat(nobody.bytes("GET", files(record), null).status()).isEqualTo(401);
        assertThat(nobody.bytes("POST", files(record) + "?name=a.zip", random(10), "Content-Type",
                "application/zip").status()).isEqualTo(401);
    }

    // ---- what is refused ----

    @Test
    void aFileThatIsTooLargeAndAnEmptyOneAreRefusedAndNothingIsStored() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        BytesResponse large = upload(owner.browser(), record, "big.zip", "application/zip",
                random(150_000));
        BytesResponse empty = upload(owner.browser(), record, "empty.zip", "application/zip", new byte[0]);

        assertThat(large.status()).as(large.text()).isEqualTo(413);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(TestStorage.keys("files/" + items.organization().id().value() + "/")).isEmpty();
        assertThat(JsonPath.<List<String>>read(owner.browser().get(files(record)).body(), "$.data")).isEmpty();
    }

    @Test
    void aProgramAScriptAPageAndADeniedNameAreRefusedWhateverTheyAreCalledAndNothingIsStored() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        List<BytesResponse> refused = List.of(
                upload(owner.browser(), record, "report.pdf", "application/pdf", "MZ\u0090\u0000program".getBytes(
                        StandardCharsets.ISO_8859_1)),
                upload(owner.browser(), record, "notes.txt", "text/plain", "#!/bin/sh\nrm -rf /".getBytes(
                        StandardCharsets.UTF_8)),
                upload(owner.browser(), record, "photo.png", "image/png", "<html><script>x</script>".getBytes(
                        StandardCharsets.UTF_8)),
                upload(owner.browser(), record, "setup.exe", "application/octet-stream", random(100)),
                upload(owner.browser(), record, "notes.txt", "text/html", "plain".getBytes(StandardCharsets.UTF_8)));

        for (BytesResponse answer : refused) {
            assertThat(answer.status()).as(answer.text()).isEqualTo(400);
            assertThat(answer.text()).doesNotContain("setup.exe");
        }
        assertThat(TestStorage.keys("files/" + items.organization().id().value() + "/")).isEmpty();
    }

    @Test
    void aHostileNameIsCleanedAndNeverBecomesAPathOrAHeaderInjection() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        BytesResponse stored = upload(owner.browser(), record, "../../etc/pass\r\nwd\"; x=.zip", "application/zip",
                random(100));
        BytesResponse back = owner.browser().bytes("GET", files(record) + "/" + idOf(stored) + "/content", null);

        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
        String name = JsonPath.read(stored.text(), "$.data.name");
        assertThat(name).doesNotContain("/").doesNotContain("\r").doesNotContain("\n").doesNotContain("\"");
        assertThat(back.header("Content-Disposition").orElse("")).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void aRecordHasAtMostTheNumberOfFilesTheSettingAllows() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        int stored = 0;
        for (int index = 0; index < 20; index++) {
            stored += upload(owner.browser(), record, "f" + index + ".zip", "application/zip", random(20 + index))
                    .status() == 201 ? 1 : 0;
        }

        BytesResponse tooMany = upload(owner.browser(), record, "one more.zip", "application/zip", random(50));

        assertThat(stored).isEqualTo(20);
        assertThat(tooMany.status()).as(tooMany.text()).isEqualTo(409);
    }

    // ---- life with the record ----

    @Test
    void whenTheRecordIsInTheRecycleBinItsFilesCannotBeReachedAndTheyComeBackWithIt() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));
        owner.browser().request("DELETE", ITEM_RECORDS + "/" + record, null);

        assertThat(owner.browser().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(404);
        owner.browser().postJson(ITEM_BIN + "/" + record + "/restore", "{}");

        assertThat(owner.browser().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(200);
    }

    @Test
    void whenTheRecordIsRemovedForGoodTheCleanUpRemovesTheBytesAfterTheRowsAndLeavesNothingReachable() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        upload(owner.browser(), record, "a.zip", "application/zip", random(100));
        upload(owner.browser(), record, "b.zip", "application/zip", random(100));
        String prefix = "files/" + items.organization().id().value() + "/";
        assertThat(TestStorage.keys(prefix)).hasSize(2);
        owner.browser().request("DELETE", ITEM_RECORDS + "/" + record, null);
        owner.browser().request("DELETE", ITEM_BIN + "/" + record, null);

        assertThat(owner("select count(*) from record_file where record_id = ?::uuid", record))
                .as("the rows go with the record at once").containsExactly("0");
        assertThat(owner("select count(*) from file_object_cleanup where tenant_id = ?::uuid",
                items.organization().id().value().toString())).as("and the bytes are queued").containsExactly("2");
        cleanup.workOn(items.organization().id());

        assertThat(TestStorage.keys(prefix)).isEmpty();
        assertThat(owner("select count(*) from file_object_cleanup where tenant_id = ?::uuid",
                items.organization().id().value().toString())).containsExactly("0");
    }

    @Test
    void deletingAFileRemovesItAtOnceAndItsBytesWhenTheCleanUpRuns() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));
        String prefix = "files/" + items.organization().id().value() + "/";

        BytesResponse removed = owner.browser().bytes("DELETE", files(record) + "/" + file, null);

        assertThat(removed.status()).isEqualTo(204);
        assertThat(owner.browser().bytes("GET", files(record) + "/" + file + "/content", null).status())
                .isEqualTo(404);
        cleanup.workOn(items.organization().id());
        assertThat(TestStorage.keys(prefix)).isEmpty();
    }

    @Test
    void anUploadThatNeverFinishedIsExpiredByTheCleanUpAndItsBytesAreRemoved() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        // The row guard makes the creation time the database's own, so it is switched off for this one statement.
        ownerRuns("alter table record_file disable trigger record_file_row_guard; "
                + "insert into record_file (tenant_id, object_api_name, record_id, name, content_type, storage_key, "
                + "created_by, updated_by, created_at) select r.tenant_id, r.object_api_name, r.id, 'half.zip', "
                + "'application/zip', 'files/' || r.tenant_id || '/' || uuidv7(), r.created_by, r.created_by, "
                + "now() - interval '3 hours' from object_record r where r.id = '" + record + "'; "
                + "alter table record_file enable trigger record_file_row_guard");

        cleanup.workOn(items.organization().id());

        assertThat(owner("select count(*) from record_file where record_id = ?::uuid", record)).containsExactly("0");
    }

    // ---- the audit trail and the store being down ----

    @Test
    void theAuditTrailHoldsIdentifiersAndASizeAndNeverANameOrContent() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        BytesResponse stored = upload(owner.browser(), record, "very private name.zip", "application/zip", random(777));
        owner.browser().bytes("GET", files(record) + "/" + idOf(stored) + "/content", null);

        List<String> uploads = auditsOf("data.file.uploaded", record);
        List<String> downloads = auditsOf("data.file.downloaded", record);

        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0)).contains("777").contains(idOf(stored)).doesNotContain("private");
        assertThat(downloads).hasSize(1);
        assertThat(downloads.get(0)).doesNotContain("private");
    }

    @Test
    void whenTheStoreIsDownAnUploadAndADownloadAnswerAServiceUnavailableNotFoundNothingIsLeftBehind()
            throws Exception {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        String file = idOf(upload(owner.browser(), record, "a.zip", "application/zip", random(100)));
        TestStorage.pause();
        BytesResponse refused;
        BytesResponse unread;
        try {
            refused = upload(owner.browser(), record, "b.zip", "application/zip", random(100));
            unread = owner.browser().bytes("GET", files(record) + "/" + file + "/content", null);
        } finally {
            TestStorage.resume();
        }

        assertThat(refused.status()).as(refused.text()).isEqualTo(503);
        assertThat(unread.status()).isEqualTo(503);
        assertThat(refused.text()).doesNotContain("Exception").doesNotContain("amazonaws").doesNotContain("http://");
        assertThat(JsonPath.<List<String>>read(owner.browser().get(files(record)).body(), "$.data[*].name"))
                .as("the failed upload left no file").containsExactly("a.zip");
    }
}
