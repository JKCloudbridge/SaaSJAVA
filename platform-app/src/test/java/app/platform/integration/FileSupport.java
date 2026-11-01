package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestFileServer;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the tests of file flows share (Sprint 29A): a connection to a real file server in the test's own process, tested
 * and switched on, with a live mapping of {@code item} to the sample object, and the short ways to put a file in an
 * inbox, make the next look due now and read how the files stand, through the same API an administrator uses.
 *
 * <p>Names, addresses and values are generic; the canary values are made up per test.
 */
public abstract class FileSupport extends ReliabilitySupport {

    protected static final String CONNECTION = "file-link";
    protected static final String LOGIN = "file-login";

    /** A connection to the server that is tested, switched on and mapped. */
    protected record Site(Linked linked, TestFileServer server) {

        public TestBrowser admin() {
            return linked.admin();
        }

        public String connection() {
            return linked.connection();
        }

        public Organization organization() {
            return linked.organization();
        }
    }

    /**
     * Makes the organization's connection to the server: the login in the credential store, a connection of the file
     * transfer type that picks files up as {@code item} and, when {@code pushes}, sends a file for every record made or
     * changed; the five directories; a test that passes; the connection on; a mapping of {@code title} and
     * {@code note}.
     */
    protected Site site(TestFileServer server, boolean pulls, boolean pushes, String format) throws Exception {
        return site(server, pulls, pushes, format, Map.of());
    }

    /** As {@link #site(TestFileServer, boolean, boolean, String)}, with more settings (for example a short wait). */
    protected Site site(TestFileServer server, boolean pulls, boolean pushes, String format,
            Map<String, String> more) throws Exception {
        Organization organization = organization();
        setAsideOtherOrganizationsEvents(organization);
        TestBrowser admin = adminOf(organization);
        Response written = admin.request("PUT", "/api/v1/credentials/" + LOGIN, "{\"value\":\""
                + TestFileServer.USER + ":" + TestFileServer.PASSWORD + "\"}");
        assertThat(written.status()).as(written.body()).isEqualTo(200);
        for (String directory : List.of("/inbox", "/processing", "/done", "/error", "/outbox")) {
            server.directory(directory);
        }
        Map<String, Object> body = definition(CONNECTION, server.address());
        body.put("connectorType", "file-transfer");
        body.put("authKind", "BASIC");
        body.put("credentialName", LOGIN);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("hostKey", server.fingerprint());
        settings.put("format", format);
        if (pulls) {
            settings.put("inboundEntity", "item");
        }
        settings.putAll(more);
        body.put("settings", settings);
        body.put("subscriptions", pushes
                ? List.of(Map.of("event", "record.created", "object", OBJECT),
                        Map.of("event", "record.updated", "object", OBJECT))
                : List.of());
        body.put("scope", List.of(Map.of("object", OBJECT, "read", true, "create", true, "update", true)));
        Response created = create(admin, body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String inbound = JsonPath.read(created.body(), "$.data.inboundId");
        Response tested = test(admin, CONNECTION);
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(tested.body(), "$.data.result")).as(tested.body()).isEqualTo("PASSED");
        Response on = enable(admin, CONNECTION, versionOf(admin, CONNECTION));
        assertThat(on.status()).as(on.body()).isEqualTo(200);
        Response saved = saveDraft(admin, CONNECTION, draftOf("item", definitionOf("EXTERNAL_WINS",
                field("title", TITLE_FIELD, "BOTH", "TEXT"), field("note", NOTE_FIELD, "BOTH", "TEXT")), 0));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        Response live = publish(admin, CONNECTION, draftVersion(saved));
        assertThat(live.status()).as(live.body()).isEqualTo(200);
        return new Site(new Linked(organization, admin, CONNECTION, inbound), server);
    }

    /** A file for the inbox in the format of the test: a header line and one line per row of three columns. */
    protected static String csv(String... rows) {
        StringBuilder text = new StringBuilder("id,title,note\n");
        for (String row : rows) {
            text.append(row).append('\n');
        }
        return text.toString();
    }

    /** A row of the file: the identifier, the title and a note. */
    protected static String row(String id, String title) {
        return id + "," + title + ",note-" + id;
    }

    /** Puts a file in the inbox a minute ago, as the other side would. */
    protected static void drop(TestFileServer server, String name, String content) throws IOException {
        server.put("/inbox", name, content, Instant.now().minusSeconds(60));
    }

    /** Makes the next look at the connection due now, instead of waiting for the time it chose. */
    protected void lookNow(Site site) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, site.organization().id());
            try (PreparedStatement due = owner.prepareStatement("update integration_poll set next_poll_at = now() - "
                    + "interval '1 second', version = version + 1 where tenant_id = ? and connection_id = "
                    + "(select id from integration_connection where tenant_id = ? and name = ?)")) {
                due.setObject(1, site.organization().id().value());
                due.setObject(2, site.organization().id().value());
                due.setString(3, CONNECTION);
                due.executeUpdate();
            }
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("The look could not be made due: " + e.getSQLState(), e);
        }
    }

    /** The files block of the connection's health, as an administrator reads it. */
    protected static String files(Site site) {
        return JsonPath.<Object>read(health(site.admin(), site.connection()).body(), "$.data.files").toString();
    }

    protected static long fileCount(Site site, String field) {
        return ((Number) JsonPath.read(health(site.admin(), site.connection()).body(), "$.data.files." + field))
                .longValue();
    }

    /** How many ledger rows of the connection are in this state; the ledger holds hashes and counts only. */
    protected int ledger(Site site, String state) {
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, site.organization().id());
            try (PreparedStatement count = owner.prepareStatement("select count(*) from integration_file f join "
                    + "integration_connection c on c.id = f.connection_id where f.tenant_id = ? and c.name = ? "
                    + "and f.state = ?")) {
                count.setObject(1, site.organization().id().value());
                count.setString(2, CONNECTION);
                count.setString(3, state);
                try (ResultSet found = count.executeQuery()) {
                    found.next();
                    return found.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The ledger could not be read: " + e.getSQLState(), e);
        }
    }

    /** The titles of the records of the sample object that hold this title (there should be at most one). */
    protected static int recordsWithTitle(TestBrowser browser, String title) {
        return recordsTitled(browser, title).size();
    }
}
