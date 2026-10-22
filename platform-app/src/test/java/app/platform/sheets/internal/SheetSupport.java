package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.solution.SolutionSupport;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the sheets endpoints share (FA3). The owner of a sheet in these tests is a record of
 * the object the shipped solution declares as a sheet owner; the generic module under test knows none of its words,
 * but the tests exercise the one owner there is. People: the administrator (who may do everything with the owner
 * object), a reader (the shipped policy that holds the read right only) and an author (a policy made here that holds
 * read and update, because the shipped policies hold the read right only until the lifecycle sprint).
 */
abstract class SheetSupport extends SolutionSupport {

    protected static final String SHEETS = "/api/v1/sheets/" + OBJECT + "/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An organization with the feature, the administrator signed in, and the shipped policies made. */
    protected record Setting(Organization organization, TestBrowser admin) {
    }

    protected Setting setting() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        return new Setting(organization, admin);
    }

    /** A record of the owner object, made by the administrator. */
    protected static String template(TestBrowser admin) {
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static String versions(String owner) {
        return SHEETS + owner + "/versions";
    }

    /** A member who holds the shipped policy that only reads. */
    protected Member reader(Setting setting) {
        return memberHolding(setting.organization(), setting.admin(), "Reader");
    }

    /** A member who may read and update the records of the owner object, by a policy made for the purpose. */
    protected Member author(Setting setting) {
        return authors(setting, 1).get(0);
    }

    /** Several such members, one account each (a sign-in limit is per account), sharing one policy. */
    protected List<Member> authors(Setting setting, int count) {
        Response policy = setting.admin().postJson(POLICIES, "{\"name\":\"authors-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"abilities\":[]}");
        assertThat(policy.status()).as(policy.body()).isEqualTo(201);
        String id = JsonPath.read(policy.body(), "$.data.id");
        Response rights = setting.admin().request("PUT", POLICIES + "/" + id + "/data-access",
                "{\"objects\":[{\"key\":\"" + OBJECT + "\",\"actions\":[\"read\",\"update\"]}],\"fields\":[]}");
        assertThat(rights.status()).as(rights.body()).isEqualTo(200);
        List<Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Member member = plainMember(setting.organization());
            give(setting.admin(), member, id);
            members.add(member);
        }
        return members;
    }

    // ---- requests ----

    protected static Response start(TestBrowser browser, String owner, int dimension) {
        return browser.postJson(versions(owner), "{\"dimension\":" + dimension + "}");
    }

    protected static Response startFrom(TestBrowser browser, String owner, Integer basedOn) {
        return browser.postJson(versions(owner), basedOn == null ? "{}" : "{\"basedOn\":" + basedOn + "}");
    }

    protected static Response read(TestBrowser browser, String owner, int number) {
        return browser.get(versions(owner) + "/" + number);
    }

    protected static Response save(TestBrowser browser, String owner, int number, long version, String grid) {
        return browser.request("PUT", versions(owner) + "/" + number + "/grid?version=" + version, grid);
    }

    /** The first version of a fresh sheet, made by the browser; returns its number. */
    protected static int firstVersion(TestBrowser browser, String owner, int dimension) {
        Response started = start(browser, owner, dimension);
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        return JsonPath.<Integer>read(started.body(), "$.data.versionNo");
    }

    protected static long rowVersion(Response summary) {
        return ((Number) JsonPath.read(summary.body(), "$.data.version")).longValue();
    }

    protected static String gridOf(Response version) {
        Object grid = JsonPath.read(version.body(), "$.data.grid");
        return JSON.writeValueAsString(grid);
    }

    /** The grid as a map, for a test to change. */
    protected static Map<String, Object> gridMap(Response version) {
        return JSON.readValue(gridOf(version), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    protected static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked") // the grid is read from our own answer, whose lists hold objects
    protected static List<Map<String, Object>> list(Map<String, Object> grid, String key) {
        return (List<Map<String, Object>>) grid.get(key);
    }

    @SuppressWarnings("unchecked") // the same
    protected static Map<String, Object> cells(Map<String, Object> grid) {
        return (Map<String, Object>) grid.get("cells");
    }

    // ---- the database, to put a version into a status that no endpoint reaches yet ----

    /**
     * Moves a version to a status as the owner role, the way the approval sprints will (the database accepts exactly
     * the moves of the lifecycle).
     */
    protected static void moveTo(Organization organization, String owner, int number, String status) {
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, organization.id());
            try (PreparedStatement update = connection.prepareStatement("update sheet_version set status = ?, "
                    + "version = version + 1, updated_by = updated_by where owner_id = ? and version_no = ?")) {
                update.setString(1, status);
                update.setObject(2, UUID.fromString(owner));
                update.setInt(3, number);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not move the version", e);
        }
    }

    /** Walks a version from draft through the lifecycle to the status. */
    protected static void walkTo(Organization organization, String owner, int number, String... path) {
        for (String status : path) {
            moveTo(organization, owner, number, status);
        }
    }

    protected static long countRows(Organization organization, String owner) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection,
                    "select count(*) from sheet_version where owner_id = ?", UUID.fromString(owner)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
