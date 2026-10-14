package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the Sprint 14 integration tests share: an organization with its administrator, an {@code Employee__c} object
 * with a few fields (text, decimal, checkbox, and a formula that cannot be written), and short ways to call the record
 * endpoints and to make members who hold only some permissions on the object, all through the real API.
 */
public abstract class RecordsSupport {

    protected static final String METADATA_OBJECTS = "/api/v1/metadata/objects";
    protected static final String EMPLOYEES = "/api/v1/objects/Employee__c";
    protected static final String RECORDS = EMPLOYEES + "/records";
    protected static final String BIN = EMPLOYEES + "/recycle-bin";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String MEMBERS = "/api/v1/members";

    /**
     * What a member whose profile is made by {@link #holderWithActions} may do with the fields: edit the six fields
     * people can write and read the formula. Field permissions are explicit (ADR-0049), so a profile with object
     * actions and no field permissions would read and write no field at all (Sprint 15).
     */
    protected static final String ALL_EMPLOYEE_FIELDS = "[" + String.join(",", List.of("name__c", "department__c",
            "salary__c", "active__c", "notes__c", "tags__c").stream()
            .map(name -> "{\"key\":\"Employee__c." + name + "\",\"actions\":[\"edit\"]}").toList())
            + ",{\"key\":\"Employee__c.doubled__c\",\"actions\":[\"read\"]}]";

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3);
        return organization;
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    protected TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    /** An organization whose administrator (who may do everything with every object) has made {@code Employee__c}. */
    protected TestBrowser adminWithEmployee(Organization organization) {
        TestBrowser admin = adminOf(organization);
        makeEmployeeObject(admin);
        return admin;
    }

    protected static void makeEmployeeObject(TestBrowser admin) {
        Response object = admin.postJson(METADATA_OBJECTS, "{\"name\":\"Employee\",\"label\":\"Employee\","
                + "\"pluralLabel\":\"Employees\",\"description\":\"\"}");
        assertThat(object.status()).as(object.body()).isEqualTo(201);
        for (String field : List.of(field("name", "TEXT", ""), field("department", "TEXT", ""),
                field("salary", "DECIMAL", ""), field("active", "BOOLEAN", ""), field("notes", "LONG_TEXT", ""),
                field("tags", "MULTI_PICKLIST", ",\"settings\":{\"values\":[{\"value\":\"a\",\"label\":\"a\","
                        + "\"active\":true},{\"value\":\"b\",\"label\":\"b\",\"active\":true}]}"),
                field("doubled", "FORMULA", ",\"settings\":{\"expression\":\"salary__c * 2\","
                        + "\"resultType\":\"DECIMAL\"}"))) {
            Response created = admin.postJson(METADATA_OBJECTS + "/Employee__c/fields", field);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
    }

    private static String field(String name, String type, String extra) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\"" + extra + "}";
    }

    // ---- members with some permissions on the object ----

    /**
     * A new member whose profile gives exactly these actions on {@code Employee__c} (for example {@code "read"},
     * {@code "create"}) and no ability.
     */
    protected Member memberWithActions(Organization organization, TestBrowser admin, String... actions) {
        return holderWithActions(organization, admin, actions).member();
    }

    /** A member and the profile that gives them their permissions (to change those permissions later). */
    protected record Holder(Member member, String profileId) {
    }

    protected Holder holderWithActions(Organization organization, TestBrowser admin, String... actions) {
        return holderWith(organization, admin, "Employee__c", ALL_EMPLOYEE_FIELDS, actions);
    }

    /**
     * A member whose profile gives exactly these actions on the object and exactly these field permissions
     * ({@code fieldsJson}: a JSON array of {@code {"key":"Object__c.field__c","actions":["read"|"edit"]}}).
     */
    protected Holder holderWith(Organization organization, TestBrowser admin, String object, String fieldsJson,
            String... actions) {
        if (actions.length == 0) {
            return holderWithMatrix(organization, admin, null);
        }
        String quoted = String.join(",", Arrays.stream(actions).map(action -> "\"" + action + "\"").toList());
        return holderWithMatrix(organization, admin, "{\"objects\":[{\"key\":\"" + object + "\",\"actions\":["
                + quoted + "]}],\"fields\":" + fieldsJson + "}");
    }

    /**
     * A member whose profile's whole data-access matrix is this body (a JSON object with {@code objects} and
     * {@code fields}), for a profile that needs more than one object in it. {@code matrixJson} null means no data
     * access at all.
     */
    protected Holder holderWithMatrix(Organization organization, TestBrowser admin, String matrixJson) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        Response profile = admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        String profileId = JsonPath.read(profile.body(), "$.data.id");
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + profileId + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        if (matrixJson != null) {
            Response matrix = admin.request("PUT", PROFILES + "/" + profileId + "/data-access", matrixJson);
            assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        }
        return new Holder(person, profileId);
    }

    // ---- change sets ----

    protected static final String SETS = "/api/v1/metadata/change-sets";

    protected static String createSet(TestBrowser admin, String name) {
        Response created = admin.postJson(SETS, "{\"name\":\"" + name + "\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    /** Adds the removal of an object ({@code item} null) or of one of its fields to the set. */
    protected static Response addRemoval(TestBrowser admin, String set, String object, String item) {
        String kind = item == null ? "DELETE_OBJECT" : "DELETE_FIELD";
        return admin.postJson(SETS + "/" + set + "/changes", "{\"kind\":\"" + kind + "\",\"objectApiName\":\""
                + object + "\"" + (item == null ? "" : ",\"itemApiName\":\"" + item + "\"") + "}");
    }

    protected static Response validate(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/validate", "{}");
    }

    protected static Response publish(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/publish", "{}");
    }

    protected static List<String> objectNames(TestBrowser browser) {
        return JsonPath.read(browser.get(METADATA_OBJECTS).body(), "$.data[*].apiName");
    }

    protected static List<String> fieldNames(TestBrowser browser, String object) {
        return JsonPath.read(browser.get(METADATA_OBJECTS + "/" + object).body(), "$.data.fields[*].apiName");
    }

    // ---- records ----

    protected static String body(String fieldsJson) {
        return "{\"fields\":" + fieldsJson + "}";
    }

    protected static Response create(TestBrowser browser, String fieldsJson) {
        return browser.postJson(RECORDS, body(fieldsJson));
    }

    /** Creates a record that must succeed and returns its identifier. */
    protected static String newRecord(TestBrowser browser, String name) {
        Response created = create(browser, "{\"name__c\":\"" + name + "\",\"department__c\":\"Sales\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }

    protected static String idOf(Response response) {
        return JsonPath.read(response.body(), "$.data.id");
    }

    protected static long versionOf(Response response) {
        return ((Number) JsonPath.read(response.body(), "$.data.version")).longValue();
    }

    protected static String update(long version, String fieldsJson) {
        return "{\"version\":" + version + ",\"fields\":" + fieldsJson + "}";
    }

    protected static Response patch(TestBrowser browser, String id, long version, String fieldsJson) {
        return browser.request("PATCH", RECORDS + "/" + id, update(version, fieldsJson));
    }

    protected static String errorCode(Response response) {
        return JsonPath.read(response.body(), "$.error.code");
    }

    // ---- what was stored beside the record (read as the owner of the database) ----

    /** The payloads of the outbox events of the type about the record, oldest first. */
    protected static List<String> eventsOf(String type, String recordId) {
        return owner("select payload::text from outbox_event where event_type = ? and payload ->> 'recordId' = ? "
                + "order by created_at, id", type, recordId);
    }

    /** The attributes of the audit records of the type about the record, oldest first. */
    protected static List<String> auditsOf(String type, String recordId) {
        return owner("select coalesce(attributes::text, '') from audit_record where event_type = ? "
                + "and record_id = ? order by occurred_at, id", type, recordId);
    }

    /** Runs a statement as the owner of the database (which bypasses row level security; the triggers still fire). */
    protected static void ownerRuns(String sql) {
        try (Connection connection = TestDatabase.ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first column of every row the query returns, as text. */
    protected static List<String> owner(String sql, Object... arguments) {
        try (Connection connection = TestDatabase.ownerConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
