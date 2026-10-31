package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What the tests of mapping, sending and receiving share (Sprint 28): a connection to the simulated external system
 * that
 * is tested and switched on, the short ways to define and publish a mapping, and a caller that signs a change the way
 * the external system would. Names, addresses and values are generic; the canary values are made up per test.
 */
public abstract class MappingSupport extends IntegrationSupport {

    protected static final String OBJECT = "ObjectA__c";
    protected static final String TITLE_FIELD = "fieldA__c";
    protected static final String NOTE_FIELD = "fieldB__c";
    protected static final String OUT_TOKEN = "out-token-" + UUID.randomUUID();
    protected static final String SIGNING_SECRET = "signing-secret-" + UUID.randomUUID();

    /** A connection that is tested, switched on, and has a mapping that is live; everything a flow test needs. */
    protected record Linked(Organization organization, TestBrowser admin, String connection, String inboundId) {
    }

    protected static String mappingsOf(String connection) {
        return ROOT + "/" + connection + "/mappings";
    }

    /** A map of one field: the external name, the field, the direction, and the type the other side carries. */
    protected static Map<String, Object> field(String external, String platform, String direction, String type) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("external", external);
        field.put("platform", platform);
        field.put("direction", direction);
        field.put("externalType", type);
        field.put("required", false);
        field.put("conversion", "NONE");
        return field;
    }

    /** A definition of a mapping of the sample object. */
    @SafeVarargs
    protected static final Map<String, Object> definitionOf(String conflict, Map<String, Object>... fields) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("conflict", conflict);
        definition.put("inboundCreate", true);
        definition.put("inboundUpdate", true);
        List<Map<String, Object>> listed = new java.util.ArrayList<>();
        for (Map<String, Object> one : fields) {
            listed.add(one);
        }
        definition.put("fields", listed);
        return definition;
    }

    protected static Map<String, Object> draftOf(String entity, Map<String, Object> definition, long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity", entity);
        body.put("definition", definition);
        body.put("version", version);
        return body;
    }

    protected static Response saveDraft(TestBrowser browser, String connection, Map<String, Object> body) {
        return browser.request("PUT", mappingsOf(connection) + "/" + OBJECT, json(body));
    }

    protected static Response publish(TestBrowser browser, String connection, long draftVersion) {
        return browser.postJson(mappingsOf(connection) + "/" + OBJECT + "/publish",
                "{\"version\":" + draftVersion + "}");
    }

    protected static long draftVersion(Response saved) {
        return ((Number) JsonPath.read(saved.body(), "$.data.draft.version")).longValue();
    }

    /**
     * Makes a connection to the service, tested and switched on, with the credentials in the store and a scope for the
     * sample object, and a live mapping of {@code title} to {@code fieldA__c} both ways (the external side is the
     * winner of a conflict, so a test of the receiver sees its change land).
     */
    protected Linked linked(FakeService service, String conflict) throws Exception {
        Organization organization = organization();
        setAsideOtherOrganizationsEvents(organization);
        TestBrowser admin = adminOf(organization);
        // Written through the real store, not an in-memory one: any application context that shares the database may
        // claim the outbox event, and each must find the credential.
        for (String[] credential : new String[][] {{"partner-token", OUT_TOKEN}, {"partner-signing", SIGNING_SECRET}}) {
            Response written = admin.request("PUT", "/api/v1/credentials/" + credential[0],
                    "{\"value\":\"" + credential[1] + "\"}");
            assertThat(written.status()).as(written.body()).isEqualTo(200);
        }
        Map<String, Object> body = definition("partner-link", service.url());
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", "partner-token");
        body.put("settings", Map.of("entityPath", "/api", "inboundCredential", "partner-signing"));
        body.put("subscriptions", List.of(Map.of("event", "record.created", "object", OBJECT),
                Map.of("event", "record.updated", "object", OBJECT)));
        body.put("scope", List.of(Map.of("object", OBJECT, "read", true, "create", true, "update", true)));
        Response created = create(admin, body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String inbound = JsonPath.read(created.body(), "$.data.inboundId");
        assertThat(test(admin, "partner-link").status()).isEqualTo(200);
        assertThat(enable(admin, "partner-link", versionOf(admin, "partner-link")).status()).isEqualTo(200);

        Response saved = saveDraft(admin, "partner-link", draftOf("item", definitionOf(conflict,
                field("title", TITLE_FIELD, "BOTH", "TEXT"), field("note", NOTE_FIELD, "BOTH", "TEXT")), 0));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        Response live = publish(admin, "partner-link", draftVersion(saved));
        assertThat(live.status()).as(live.body()).isEqualTo(200);
        return new Linked(organization, admin, "partner-link", inbound);
    }

    /**
     * Events that earlier tests left pending (their contexts ran no relay) are claimed oldest first, so the first relay
     * to start would work through all of them before it reached this test's event. They belong to finished tests; they
     * are marked delivered so the wait here depends on this test alone.
     */
    private static void setAsideOtherOrganizationsEvents(Organization organization) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement("update outbox_event set status = 'DELIVERED', "
                        + "delivered_at = now(), version = version + 1 where status = 'PENDING' and tenant_id <> ?")) {
            statement.setObject(1, organization.id().value());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The old events could not be set aside: " + e.getSQLState(), e);
        }
    }

    // ---- the receiver, called the way the external system would ----

    /** The three headers of a signed change, for the time, nonce and body given. */
    protected static String[] signed(String secret, String timestamp, String nonce, String entity, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] message = ("v1\n" + timestamp + "\n" + nonce + "\n" + entity + "\n" + body)
                    .getBytes(StandardCharsets.UTF_8);
            return new String[] {"X-Platform-Timestamp", timestamp, "X-Platform-Nonce", nonce, "X-Platform-Signature",
                "sha256=" + HexFormat.of().formatHex(mac.doFinal(message))};
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Sends a signed change at the platform host: the host never names the organization. */
    protected Response receive(String inboundId, String entity, String body, String... headers) {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST).request("POST",
                "/api/v1/inbound/" + inboundId + "/" + entity, body, headers);
    }

    protected Response receiveSigned(String inboundId, String entity, String body) {
        return receive(inboundId, entity, body, signed(SIGNING_SECRET, Long.toString(System.currentTimeMillis() / 1000),
                "n-" + UUID.randomUUID().toString().replace("-", ""), entity, body));
    }

    /** The ids of the records of the sample object that hold the title (the first field). */
    protected static List<String> recordsTitled(TestBrowser browser, String title) {
        Response all = browser.get("/api/v1/objects/" + OBJECT + "/records?limit=100");
        assertThat(all.status()).as(all.body()).isEqualTo(200);
        return JsonPath.read(all.body(), "$.data[?(@.fields.fieldA__c=='" + title + "')].id");
    }
}
