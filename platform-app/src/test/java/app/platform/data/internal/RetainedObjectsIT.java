package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.solution.SolutionSupport;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retained-object rule (Functional track FA1, ADR-0174 section 7, ADR-0181): the records of an object that
 * declares {@code retainRecords} are kept. Four layers, each tried on its own: the decision API and the
 * access-policy editor never
 * give the delete right; the record store answers every way of removing, restoring over or purging with fixed words;
 * the recycle-bin retention job leaves them alone; and the database itself refuses a physical delete and a soft delete,
 * to the application role and to the owner, so that no future code path, bug or administrator can remove one.
 */
@PlatformIntegrationTest
class RetainedObjectsIT extends SolutionSupport {

    private static final String KEPT = "The records of this object are kept.";
    private static final String BIN = "/api/v1/objects/" + OBJECT + "/recycle-bin";

    @Autowired
    private RecycleBinRetention retention;

    @Autowired
    private RetainedObjects retained;

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private TransactionTemplate transaction;

    private static String create(TestBrowser browser, String name) {
        Response created = browser.postJson(RECORDS, "{\"fields\":{\"name\":\"" + name + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    private static void assertKept(Response refused) {
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("CONFLICT");
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).isEqualTo(KEPT);
    }

    // ---- the record store ----

    @Test
    void deletingARecordOfAKeptObjectIsRefusedWithFixedWordsEvenForTheAdministrator() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String id = create(admin, newName());

        Response refused = admin.request("DELETE", RECORDS + "/" + id, null);

        assertKept(refused);
        assertThat(admin.get(RECORDS + "/" + id).status()).as("the record is still there").isEqualTo(200);
    }

    @Test
    void bulkDeleteRestoreOverPurgeAndTheRecycleBinAreRefusedTheSameWay() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String id = create(admin, newName());

        assertKept(admin.postJson(RECORDS + "/bulk",
                "{\"operation\":\"DELETE\",\"records\":[{\"id\":\"" + id + "\"}]}"));
        assertKept(admin.postJson(BIN + "/" + id + "/restore", "{}"));
        assertKept(admin.request("DELETE", BIN + "/" + id, null));
        assertKept(admin.get(BIN));
        assertThat(admin.get(RECORDS + "/" + id).status()).isEqualTo(200);
    }

    @Test
    void aMemberWhoHoldsEverythingOnTheObjectIsRefusedToo() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String id = create(admin, newName());
        String profile = JsonPath.read(admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[]}").body(), "$.data.id");
        Member member = plainMember(organization);
        assertThat(admin.request("PUT", MEMBERS + "/" + member.membership() + "/profile",
                "{\"profileId\":\"" + profile + "\"}").status()).isIn(200, 204);
        Response modifyAll = admin.request("PUT", "/api/v1/profiles/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"" + OBJECT + "\",\"actions\":[\"modify-all\"]}],\"fields\":[]}");
        assertThat(modifyAll.status()).as("modify-all is not the delete right and is accepted").as(modifyAll.body())
                .isEqualTo(200);

        TestBrowser holder = as(organization, member);

        assertThat(holder.get(RECORDS + "/" + id).status()).isEqualTo(200);
        assertKept(holder.request("DELETE", RECORDS + "/" + id, null));
        assertThat(mayDelete(organization, member)).as("the decision API gives no delete right").isFalse();
        assertThat(JsonPath.<List<String>>read(holder.get("/api/v1/data-access/mine").body(),
                "$.data.objects[?(@.key=='" + OBJECT + "')].actions[*]")).doesNotContain("delete").contains("read");
    }

    @Test
    void theDecisionApiNeverAllowsDeletingEvenToTheAdministratorWhoHoldsEverything() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);

        assertThat(mayDelete(organization, organization.admin())).isFalse();
        Response form = admin.get("/api/v1/objects/" + OBJECT + "/form");
        assertThat(JsonPath.<Boolean>read(form.body(), "$.data.actions.delete")).as(form.body()).isFalse();
        assertThat(JsonPath.<Boolean>read(form.body(), "$.data.actions.create")).isTrue();
    }

    @Test
    void aKeptRecordCanStillBeChangedButNothingMarksItRetiredExceptTheRetireAct() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String id = create(admin, newName());
        Response read = admin.get(RECORDS + "/" + id);
        long version = ((Number) JsonPath.read(read.body(), "$.data.version")).longValue();

        Response renamed = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + version + ",\"fields\":{\"name\":\"renamed\"}}");
        Response retiredByHand = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + (version + 1) + ",\"fields\":{\"retired\":true}}");

        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(retiredByHand.status()).as("only the retire act marks a template retired (ADR-0188)").isEqualTo(400);
        assertThat(admin.get(RECORDS + "/" + id).status()).as("a kept record stays readable").isEqualTo(200);
    }

    @Test
    void anObjectThatDoesNotKeepItsRecordsCanStillBeDeleted() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(admin.postJson("/api/v1/metadata/objects", "{\"name\":\"Thing\",\"label\":\"Thing\","
                + "\"pluralLabel\":\"Things\",\"description\":\"\"}").status()).isEqualTo(201);
        Response made = admin.postJson("/api/v1/objects/Thing__c/records", "{\"fields\":{}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String id = JsonPath.read(made.body(), "$.data.id");

        Response deleted = admin.request("DELETE", "/api/v1/objects/Thing__c/records/" + id, null);

        assertThat(deleted.status()).as(deleted.body()).isIn(200, 204);
    }

    // ---- the access-policy editor and API ----

    @Test
    void theDeleteRightCannotBeGivenOnAKeptObjectToAProfileAPolicyOrAMember() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        String delete = "{\"objects\":[{\"key\":\"" + OBJECT + "\",\"actions\":[\"read\",\"delete\"]}],\"fields\":[]}";
        String profile = JsonPath.read(admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[]}").body(), "$.data.id");
        String policy = JsonPath.read(admin.postJson(POLICIES, "{\"name\":\"own-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"abilities\":[]}").body(), "$.data.id");

        List<Response> refusals = List.of(
                admin.request("PUT", "/api/v1/profiles/" + profile + "/data-access", delete),
                admin.request("PUT", POLICIES + "/" + policy + "/data-access", delete),
                // The administrator trying to give themselves the right.
                admin.request("PUT", MEMBERS + "/" + organization.admin().membership() + "/data-access", delete));

        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
            assertThat(refused.body()).contains("cannot be given the delete right");
        }
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES + "/" + policy + "/data-access").body(),
                "$.data.objects[*].key")).as("nothing was stored").isEmpty();
    }

    @Test
    void theCatalogueSaysWhichObjectsAreKeptSoTheScreenOffersNoControlForThem() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);

        Response catalogue = admin.get("/api/v1/data-catalogue");

        assertThat(JsonPath.<List<Boolean>>read(catalogue.body(),
                "$.data.objects[?(@.key=='" + OBJECT + "')].retained"))
                .containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(catalogue.body(), "$.data.objects[?(@.key=='ObjectA__c')].retained"))
                .containsExactly(false);
    }

    @Test
    void theOtherRightsOnAKeptObjectAreAcceptedAsBefore() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        String policy = JsonPath.read(admin.postJson(POLICIES, "{\"name\":\"own-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"abilities\":[]}").body(), "$.data.id");

        Response set = admin.request("PUT", POLICIES + "/" + policy + "/data-access", "{\"objects\":[{\"key\":\""
                + OBJECT + "\",\"actions\":[\"read\",\"create\",\"update\"]}],\"fields\":[]}");

        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(set.body(), "$.data.objects[0].actions"))
                .containsExactlyInAnyOrder("read", "create", "update");
    }

    // ---- the database ----

    @Test
    void theDatabaseRefusesAPhysicalDeleteToTheApplicationRoleAndToTheOwner() throws SQLException {
        Organization organization = organizationWithFeature();
        UUID id = UUID.fromString(create(adminOf(organization), newName()));

        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "delete from object_record where id = ?", id)))
                .hasMessageContaining("retained object guard");
        assertThatThrownBy(() -> ownerRuns("delete from object_record where id = '" + id + "'"))
                .hasMessageContaining("retained object guard");
        assertThat(ownerCount("select count(*) from object_record where id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesARawSoftDeleteToTheApplicationRoleAndToTheOwner() throws SQLException {
        Organization organization = organizationWithFeature();
        UUID id = UUID.fromString(create(adminOf(organization), newName()));

        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update object_record set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where id = ?", UUID.randomUUID(), UUID.randomUUID(), id)))
                .hasMessageContaining("retained object guard");
        assertThatThrownBy(() -> ownerRuns("update object_record set deleted_at = now(), deleted_by = '"
                + UUID.randomUUID() + "', version = version + 1, updated_by = '" + UUID.randomUUID() + "' where id = '"
                + id + "'")).hasMessageContaining("retained object guard");
        assertThat(ownerCount("select count(*) from object_record where id = '" + id
                + "' and deleted_at is null")).isEqualTo(1);
    }

    @Test
    void anOrdinaryUpdateOfAKeptRecordIsNotSlowedOrRefusedByTheGuard() throws SQLException {
        Organization organization = organizationWithFeature();
        UUID id = UUID.fromString(create(adminOf(organization), newName()));

        int updated = TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "update object_record set version = version + 1, updated_by = ? where id = ?", UUID.randomUUID(), id));

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void theDatabaseStillSoftDeletesTheRecordsOfAnObjectThatIsNotKept() throws SQLException {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(admin.postJson("/api/v1/metadata/objects", "{\"name\":\"Thing\",\"label\":\"Thing\","
                + "\"pluralLabel\":\"Things\",\"description\":\"\"}").status()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(admin.postJson("/api/v1/objects/Thing__c/records",
                "{\"fields\":{}}").body(), "$.data.id"));

        int updated = TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "update object_record set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where id = ?", UUID.randomUUID(), UUID.randomUUID(), id));

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void theListOfKeptObjectsHasTheDeclaredObjectOnceAndOnlyGrows() throws SQLException {
        assertThat(retained.sync()).as("starting again writes nothing").isZero();
        assertThat(ownerCount("select count(*) from retained_object where object_api_name = 'InspectionTemplate' "
                + "and deleted_at is null")).isEqualTo(1);
        assertThat(ownerCount("select count(*) from retained_object where object_api_name = 'InspectionFill' "
                + "and deleted_at is null")).isEqualTo(1);
        assertThat(ownerCount("select count(*) from retained_object")).isEqualTo(2);

        assertThatThrownBy(() -> ownerRuns("update retained_object set version = version + 1, updated_by = '"
                + UUID.randomUUID() + "'")).hasMessageContaining("only grows");
        assertThatThrownBy(() -> ownerRuns("delete from retained_object")).hasMessageContaining("only grows");
        assertThatThrownBy(() -> TenantFixtures.asNobody(connection -> TenantFixtures.update(connection,
                "delete from retained_object"))).hasMessageContaining("only grows");
        assertThatThrownBy(() -> TenantFixtures.asNobody(connection -> TenantFixtures.update(connection,
                "update retained_object set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ?",
                UUID.randomUUID(), UUID.randomUUID()))).hasMessageContaining("only grows");
        assertThat(ownerCount("select count(*) from retained_object where deleted_at is null")).isEqualTo(2);
    }

    // ---- the recycle-bin retention job ----

    @Test
    void theRetentionJobLeavesAKeptRecordAloneAndStillPurgesTheOthers() throws SQLException {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        UUID kept = UUID.fromString(create(admin, newName()));
        assertThat(admin.postJson("/api/v1/metadata/objects", "{\"name\":\"Thing\",\"label\":\"Thing\","
                + "\"pluralLabel\":\"Things\",\"description\":\"\"}").status()).isEqualTo(201);
        UUID ordinary = UUID.fromString(JsonPath.read(admin.postJson("/api/v1/objects/Thing__c/records",
                "{\"fields\":{}}").body(), "$.data.id"));
        assertThat(admin.request("DELETE", "/api/v1/objects/Thing__c/records/" + ordinary, null).status())
                .isIn(200, 204);
        // A kept record in the recycle bin cannot be made through the product, which is the point. A record that was
        // binned before the object was kept is the case the job must still leave alone, so it is staged here with the
        // guards switched off for the staging only, as the owner of the database.
        stageBinned(kept, ordinary);

        int removed = retention.runOnce();

        assertThat(removed).as("the ordinary record is purged, so the batch did not fail on the kept one")
                .isGreaterThanOrEqualTo(1);
        assertThat(ownerCount("select count(*) from object_record where id = '" + ordinary + "'")).isZero();
        assertThat(ownerCount("select count(*) from object_record where id = '" + kept + "'")).isEqualTo(1);
    }

    private static void stageBinned(UUID kept, UUID ordinary) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("alter table object_record disable trigger user");
            try {
                statement.executeUpdate("update object_record set deleted_at = now() - interval '400 days', "
                        + "deleted_by = updated_by where id in ('" + kept + "', '" + ordinary + "')");
            } finally {
                statement.execute("alter table object_record enable trigger user");
            }
        }
    }

    // ---- helpers ----

    private boolean mayDelete(Organization organization, Member member) {
        return Boolean.TRUE.equals(contexts.call(TenantContext.of(organization.id()),
                () -> transaction.execute(status -> decisions.can(member.membership(), OBJECT, ObjectAction.DELETE)
                        .allowed())));
    }

    private static void ownerRuns(String sql) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long ownerCount(String sql) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
