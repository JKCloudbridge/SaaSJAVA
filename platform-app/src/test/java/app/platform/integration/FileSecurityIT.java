package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestFileServer;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a file server must never be able to make the platform do (Sprint 29A, ADR-0237 to ADR-0239): sign in to a server
 * that shows another key (and never try again), keep sending a credential the server refuses, be reached through an
 * address that is not a public file server's, or pick up a file by a name that is not one the connection takes.
 * A key and a password are both ways to sign in. Everything the tests read is what an administrator reads.
 */
@FileFlowTest
class FileSecurityIT extends FileSupport {

    private TestFileServer server;

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aServerThatShowsAnotherKeyIsNeverSignedIntoAndNeverTriedAgainUntilAPersonTestsIt() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String connection = site.connection();
        server.wrongHostKey();
        int before = server.loginAttempts();

        String title = "title-" + UUID.randomUUID();
        created(admin, title);
        await(site.linked(), "the connection to be stopped", () -> "FAILING".equals(state(admin, connection)));

        Thread.sleep(5_500);
        assertThat(server.loginAttempts()).as("the credential was never shown to a server with another key")
                .isEqualTo(before);
        assertThat(count(admin, connection, "PENDING")).as("what was to be sent waits").isEqualTo(1);
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(server.visibleNames("/outbox")).isEmpty();
        Response stillWrong = test(admin, connection);
        assertThat(JsonPath.<String>read(stillWrong.body(), "$.data.result")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(stillWrong.body(), "$.data.reason")).isEqualTo("host_key_mismatch");
        assertThat(server.loginAttempts()).isEqualTo(before);

        // The right server is back: a test that passes brings the connection back, and the waiting file is written.
        server.up();
        Thread.sleep(5_500);
        Response passed = test(admin, connection);
        assertThat(JsonPath.<String>read(passed.body(), "$.data.result")).as(passed.body()).isEqualTo("PASSED");
        await(site.linked(), "the file to be written", () -> count(admin, connection, "DONE") == 1);
        assertThat(server.visibleNames("/outbox")).hasSize(1);
    }

    @Test
    void aCredentialTheServerRefusesIsShownOnceAndTheConnectionWaitsForAPerson() throws Exception {
        Site site = site(server, false, true, "csv");
        TestBrowser admin = site.admin();
        String connection = site.connection();
        server.rejectLogins(true);
        int before = server.loginAttempts();

        created(admin, "title-" + UUID.randomUUID());
        await(site.linked(), "the connection to be stopped", () -> "FAILING".equals(state(admin, connection)));

        Thread.sleep(3_000);
        assertThat(server.loginAttempts()).as("one refusal, then no more tries that could lock the account")
                .isEqualTo(before + 1);
        assertThat(count(admin, connection, "DEAD_LETTERED")).as("the data was not at fault").isZero();
        assertThat(count(admin, connection, "PENDING")).isEqualTo(1);

        server.rejectLogins(false);
        Thread.sleep(5_500);
        Response passed = test(admin, connection);
        assertThat(JsonPath.<String>read(passed.body(), "$.data.result")).as(passed.body()).isEqualTo("PASSED");
        await(site.linked(), "the file to be written", () -> count(admin, connection, "DONE") == 1);
    }

    @Test
    void aPrivateKeyCanSignInInsteadOfAPassword() throws Exception {
        String key = server.allowGeneratedKey();
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Response written = admin.request("PUT", "/api/v1/credentials/key-login", json(Map.of("value",
                TestFileServer.USER + "\n" + key)));
        assertThat(written.status()).as(written.body()).isEqualTo(200);
        for (String directory : List.of("/inbox", "/processing", "/done", "/error", "/outbox")) {
            server.directory(directory);
        }
        Map<String, Object> body = definition("key-link", server.address());
        body.put("connectorType", "file-transfer");
        body.put("authKind", "SSH_KEY");
        body.put("credentialName", "key-login");
        body.put("settings", Map.of("hostKey", server.fingerprint()));
        assertThat(create(admin, body).status()).isEqualTo(201);

        Response tested = test(admin, "key-link");

        assertThat(JsonPath.<String>read(tested.body(), "$.data.result")).as(tested.body()).isEqualTo("PASSED");
        assertThat(tested.body()).doesNotContain("BEGIN").doesNotContain(TestFileServer.PASSWORD);
    }

    @Test
    void aCredentialThatIsNotAUserAndAKeyIsNotUsable() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        admin.request("PUT", "/api/v1/credentials/bad-login", json(Map.of("value", "no-colon-in-this")));
        server.directory("/outbox");
        Map<String, Object> body = definition("bad-link", server.address());
        body.put("connectorType", "file-transfer");
        body.put("authKind", "BASIC");
        body.put("credentialName", "bad-login");
        body.put("settings", Map.of("hostKey", server.fingerprint()));
        assertThat(create(admin, body).status()).isEqualTo(201);

        Response tested = test(admin, "bad-link");

        assertThat(JsonPath.<String>read(tested.body(), "$.data.reason")).isEqualTo("credential_unusable");
        assertThat(server.loginAttempts()).as("nothing was sent to the server").isZero();
    }

    @Test
    void anAddressThatIsNotAPublicFileServerIsRefusedWhenTheConnectionIsSaved() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        admin.request("PUT", "/api/v1/credentials/file-login", json(Map.of("value", "user-a:password-a")));
        String canary = "canary-" + UUID.randomUUID().toString().substring(0, 8);

        for (String address : List.of("https://files.example.com", "http://files.example.com",
                "sftp://user-a@files.example.com", "sftp://10.20.30.40", "sftp://files.example.com/" + canary,
                "sftp://files.example.com?x=" + canary, "sftp://localhost", "sftp://files.internal",
                "sftp://2130706433", "sftp://files.example.com:0", "sftp://files.example.com:99999",
                "ftp://files.example.com")) {
            Response refused = create(admin, file("link-" + UUID.randomUUID().toString().substring(0, 6), address));
            assertThat(refused.status()).as(address + ": " + refused.body()).isEqualTo(400);
            assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields")).containsKey("baseUrl");
            assertThat(refused.body()).doesNotContain(canary).doesNotContain("10.20.30.40");
        }
        // And a web service is not reached by a file address.
        Map<String, Object> web = definition("web-link", "sftp://files.example.com");
        Response wrongScheme = create(admin, web);
        assertThat(wrongScheme.status()).isEqualTo(400);

        Response good = create(admin, file("good-link", "sftp://files.example.com:2222"));
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(good.body(), "$.data.baseUrl")).isEqualTo("sftp://files.example.com:2222");
    }

    @Test
    void aDirectoryOrAFingerprintThatIsNotPlainIsRefusedWhenTheConnectionIsSaved() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        admin.request("PUT", "/api/v1/credentials/file-login", json(Map.of("value", "user-a:password-a")));
        String canary = "canary-" + UUID.randomUUID().toString().substring(0, 8);

        for (String directory : List.of("/in/../etc", "/in/./x", "in", "/a//b", "/a b", "/in/" + canary + "/..",
                "/in?x=1", "../" + canary, "/" + "a".repeat(80), "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u")) {
            Map<String, Object> body = file("link-" + UUID.randomUUID().toString().substring(0, 6),
                    "sftp://files.example.com");
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("hostKey", TestFileServer.strangerFingerprint());
            settings.put("inboxDirectory", directory);
            body.put("settings", settings);
            Response refused = create(admin, body);
            assertThat(refused.status()).as(directory + ": " + refused.body()).isEqualTo(400);
            assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields"))
                    .containsKey("settings.inboxDirectory");
            assertThat(refused.body()).doesNotContain(canary);
        }
        Map<String, Object> clash = file("clash-link", "sftp://files.example.com");
        clash.put("settings", Map.of("hostKey", TestFileServer.strangerFingerprint(), "inboxDirectory", "/same",
                "doneDirectory", "/same"));
        assertThat(create(admin, clash).status()).as("two directories that are one").isEqualTo(400);

        for (String fingerprint : List.of("", "SHA256:short", "MD5:aa:bb", "sha256:" + "A".repeat(43),
                "SHA256:" + "A".repeat(43) + " extra", "SHA256:" + "!".repeat(43))) {
            Map<String, Object> body = file("link-" + UUID.randomUUID().toString().substring(0, 6),
                    "sftp://files.example.com");
            body.put("settings", Map.of("hostKey", fingerprint));
            Response refused = create(admin, body);
            assertThat(refused.status()).as(fingerprint + ": " + refused.body()).isEqualTo(400);
            assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields"))
                    .containsKey("settings.hostKey");
        }
    }

    @Test
    void aFileWithAnotherEndingOrAnOddNameIsNeverPickedUpAndNothingOutsideTheInboxIsRead() throws Exception {
        Site site = site(server, true, false, "csv");
        String title = "title-" + UUID.randomUUID();
        for (String name : List.of("a b.csv", "-dash.csv", "x;y.csv", "xé.csv", "..csv", ".x.csv")) {
            try {
                server.put("/inbox", name, csv(row("1", title)), java.time.Instant.now().minusSeconds(60));
            } catch (java.io.IOException | java.nio.file.InvalidPathException cannotMakeThisName) {
                // A name the test machine's file system cannot hold is not one a server of it can show either.
            }
        }
        // A file one level above the inbox is not in any listing of it.
        server.put("/", "outside.csv", csv(row("2", title)), java.time.Instant.now().minusSeconds(60));
        server.put("/inbox", "ok.csv", csv(row("3", "ok-" + title)), java.time.Instant.now().minusSeconds(60));

        lookNow(site);
        await(site.linked(), "the one good file", () -> fileCount(site, "filesDone") == 1);

        assertThat(recordsWithTitle(site.admin(), title)).as("none of the odd ones").isZero();
        assertThat(recordsWithTitle(site.admin(), "ok-" + title)).isEqualTo(1);
        assertThat(server.visibleNames("/")).contains("outside.csv");
    }

    @Test
    void aLinkInTheInboxIsNotFollowed() throws Exception {
        Site site = site(server, true, false, "csv");
        String title = "title-" + UUID.randomUUID();
        java.nio.file.Path target = server.put("/", "secret-target.csv", csv(row("1", title)),
                java.time.Instant.now().minusSeconds(60));
        try {
            java.nio.file.Files.createSymbolicLink(server.directory("/inbox").resolve("link.csv"), target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException noLinksHere) {
            org.junit.jupiter.api.Assumptions.abort("This machine cannot make a symbolic link");
        }

        lookNow(site);
        Thread.sleep(4_000);

        assertThat(recordsWithTitle(site.admin(), title)).as("a link is not a file the platform picks up").isZero();
        assertThat(server.visibleNames("/inbox")).containsExactly("link.csv");
        assertThat(server.visibleNames("/")).contains("secret-target.csv");
    }

    private static Map<String, Object> file(String name, String address) {
        Map<String, Object> body = definition(name, address);
        body.put("connectorType", "file-transfer");
        body.put("authKind", "BASIC");
        body.put("credentialName", "file-login");
        body.put("settings", Map.of("hostKey", TestFileServer.strangerFingerprint()));
        return body;
    }
}
