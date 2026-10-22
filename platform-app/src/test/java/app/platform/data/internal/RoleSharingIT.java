package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shares of a record to a role, and to a role and the roles below it (Functional track FA2B, ADR-0178, ADR-0185),
 * through the real API: who reads, who changes, who never deletes, what a move or a removal of a role does, what the
 * list of roles and groups to choose from says and to whom. Every claim is checked against the list, the query, the
 * single read and the decision API, which must give the same answer.
 *
 * <p>The role tree of every test (the roles are named generically):
 * <pre>
 *   role-top
 *    +-- role-middle
 *         +-- role-leaf-a   (two people)
 *         +-- role-leaf-b   (one person)
 * </pre>
 * The object is Private with the hierarchy switched off, so a share is the only way to a record besides ownership.
 */
@PlatformIntegrationTest
class RoleSharingIT extends RecordSecuritySupport {

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    private record Crew(Items items, Person author, Person top, Person middle, Person leafA1, Person leafA2,
            Person leafB, Person outsider, String roleTop, String roleMiddle, String roleLeafA, String roleLeafB,
            String record) {

        Map<String, Person> readers() {
            Map<String, Person> readers = new LinkedHashMap<>();
            readers.put("top", top);
            readers.put("middle", middle);
            readers.put("leafA1", leafA1);
            readers.put("leafA2", leafA2);
            readers.put("leafB", leafB);
            readers.put("outsider", outsider);
            return readers;
        }
    }

    private Crew crew() {
        Items items = items();
        TestBrowser admin = items.admin();
        share(admin, "PRIVATE", false);
        Person author = worker(items.organization(), admin);
        Person top = worker(items.organization(), admin);
        Person middle = worker(items.organization(), admin);
        Person leafA1 = worker(items.organization(), admin);
        Person leafA2 = worker(items.organization(), admin);
        Person leafB = worker(items.organization(), admin);
        Person outsider = worker(items.organization(), admin);
        String roleTop = role(admin, "role-top", null);
        String roleMiddle = role(admin, "role-middle", roleTop);
        String roleLeafA = role(admin, "role-leaf-a", roleMiddle);
        String roleLeafB = role(admin, "role-leaf-b", roleMiddle);
        putInRole(admin, top, roleTop);
        putInRole(admin, middle, roleMiddle);
        putInRole(admin, leafA1, roleLeafA);
        putInRole(admin, leafA2, roleLeafA);
        putInRole(admin, leafB, roleLeafB);
        String record = newItem(author.browser(), "record-1");
        return new Crew(items, author, top, middle, leafA1, leafA2, leafB, outsider, roleTop, roleMiddle, roleLeafA,
                roleLeafB, record);
    }

    private static String sharesOf(String record) {
        return ITEM_RECORDS + "/" + record + "/shares";
    }

    private static String body(String type, String grantee, String level) {
        return "{\"granteeType\":\"" + type + "\",\"granteeId\":\"" + grantee + "\",\"accessLevel\":\"" + level + "\"}";
    }

    private static Response shareWith(TestBrowser browser, String record, String type, String grantee, String level) {
        return browser.request("PUT", sharesOf(record), body(type, grantee, level));
    }

    private static void shared(TestBrowser browser, String record, String type, String grantee, String level) {
        Response response = shareWith(browser, record, type, grantee, level);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /** What the decision API says: the answer of the security module for one record, through the same statement. */
    private boolean decides(Crew crew, Person person, ObjectAction action) {
        TenantContext context = new TenantContext(crew.items().organization().id(),
                person.member().person().user().id(), person.member().membership());
        return contexts.call(context, () -> decisions.can(person.member().membership(), "Item__c", action,
                new RecordRef(UUID.fromString(crew.record()))).allowed());
    }

    /** Whether the person reads the record: the list, the query, the single read and the decision API must agree. */
    private boolean readsIt(Crew crew, Person person) {
        boolean single = reads(person.browser(), crew.record());
        assertThat(listed(person.browser()).contains(crew.record())).as("the list agrees").isEqualTo(single);
        assertThat(queried(person.browser()).contains(crew.record())).as("the query agrees").isEqualTo(single);
        assertThat(decides(crew, person, ObjectAction.READ)).as("the decision agrees").isEqualTo(single);
        return single;
    }

    /** The names of the people (not the author) who read the record. */
    private List<String> whoReads(Crew crew) {
        List<String> names = new ArrayList<>();
        crew.readers().forEach((name, person) -> {
            if (readsIt(crew, person)) {
                names.add(name);
            }
        });
        return names;
    }

    private static long versionOf(TestBrowser browser, String record) {
        return JsonPath.<Number>read(browser.get(ITEM_RECORDS + "/" + record).body(), "$.data.version").longValue();
    }

    /** A change by the person, with the version the author reads (the person may not be able to read it). */
    private static Response change(Crew crew, TestBrowser browser, String name) {
        return browser.request("PATCH", ITEM_RECORDS + "/" + crew.record(), "{\"version\":"
                + versionOf(crew.author().browser(), crew.record()) + ",\"fields\":{\"name__c\":\"" + name
                + "\"}}");
    }

    // ---- who reads ----

    @Test
    void aShareToARoleIsReadByTheMembersWhoseOwnRoleItIsAndByNobodyElse() {
        Crew crew = crew();
        assertThat(whoReads(crew)).as("before any share").isEmpty();

        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafA(), "READ");

        assertThat(whoReads(crew)).containsExactly("leafA1", "leafA2");
    }

    @Test
    void aMemberInARoleAboveOrBelowOrBesideTheSharedRoleDoesNotReadItThroughAShareToThatRoleAlone() {
        Crew crew = crew();

        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleMiddle(), "READ");

        assertThat(whoReads(crew)).as("only role-middle itself: not role-top above it, not the leaves below it")
                .containsExactly("middle");
    }

    @Test
    void aShareToARoleAndTheRolesBelowItIsReadByThatRoleAndEveryRoleBelowAndByNobodyElse() {
        Crew crew = crew();

        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(), "READ");

        assertThat(whoReads(crew)).containsExactly("middle", "leafA1", "leafA2", "leafB");
    }

    @Test
    void aShareToTheTopRoleAndTheRolesBelowItIsReadByEveryoneWithARoleAndNotByAMemberWithNone() {
        Crew crew = crew();

        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleTop(), "READ");

        assertThat(whoReads(crew)).containsExactly("top", "middle", "leafA1", "leafA2", "leafB");
    }

    @Test
    void theHierarchyStillGivesTheRolesAboveTheRecordsOfThoseBelowAndNothingMore() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        String byLeaf = newItem(crew.leafA1().browser(), "leaf-1");

        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafB(), "READ");

        assertThat(reads(crew.top().browser(), byLeaf)).as("a record owned below, through the hierarchy").isTrue();
        assertThat(reads(crew.top().browser(), crew.record())).as("a share to a role below gives nothing upwards")
                .isFalse();
        assertThat(reads(crew.leafB().browser(), crew.record())).isTrue();
    }

    @Test
    void aShareIsOnTheRecordItNamesAndOnNoOtherRecordOfTheObject() {
        Crew crew = crew();
        String other = newItem(crew.author().browser(), "record-2");

        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleTop(), "READ");

        assertThat(reads(crew.leafA1().browser(), crew.record())).isTrue();
        assertThat(reads(crew.leafA1().browser(), other)).as("a record the share does not name").isFalse();
        assertThat(listed(crew.leafA1().browser())).containsExactly(crew.record());
    }

    @Test
    void theStatementMatchesAShareOnItsKindAndItsIdentifierTogether() throws SQLException {
        Crew crew = crew();
        String group = group(crew.items().admin(), "group-a", crew.leafB());
        UUID tenant = crew.items().organization().id().value();
        UUID record = UUID.fromString(crew.record());
        UUID actor = UUID.fromString(crew.author().userId());

        // Rows no endpoint would make: a share of one kind that carries the identifier of another kind.
        for (Object[] odd : new Object[][] {{"GROUP", crew.roleLeafA()}, {"USER", crew.roleLeafA()},
            {"ROLE", group}, {"ROLE_AND_SUBORDINATES", group}, {"ROLE", crew.leafA1().userId()}}) {
            TenantFixtures.asTenant(crew.items().organization().id(), connection -> TenantFixtures.update(connection,
                    "insert into record_share (tenant_id, object_api_name, record_id, grantee_type, grantee_id, "
                            + "access_level, created_by, updated_by) values (?, 'Item__c', ?, ?, ?, 1, ?, ?) "
                            + "on conflict do nothing", tenant, record, odd[0], UUID.fromString((String) odd[1]),
                    actor, actor));
        }

        assertThat(whoReads(crew)).as("none of them is addressed to the people whose identifier it carries")
                .isEmpty();
    }

    @Test
    void theDatabaseKeepsTheKindsAndOnlyLetsAShareToARoleChangeBetweenTheTwoRoleKinds() throws SQLException {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleMiddle(), "READ");
        UUID role = UUID.fromString(crew.roleMiddle());
        UUID record = UUID.fromString(crew.record());

        Integer changed = TenantFixtures.asTenant(crew.items().organization().id(), connection ->
                TenantFixtures.update(connection, "update record_share set grantee_type = 'ROLE_AND_SUBORDINATES', "
                        + "version = version + 1 where record_id = ? and grantee_id = ?", record, role));
        assertThat(changed).as("the one change that is allowed").isEqualTo(1);
        for (String refused : List.of("GROUP", "USER")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantFixtures.asTenant(
                    crew.items().organization().id(), connection -> TenantFixtures.update(connection,
                            "update record_share set grantee_type = '" + refused + "', version = version + 1 "
                                    + "where record_id = ? and grantee_id = ?", record, role)))
                    .as("a role share is not turned into a share of another kind: " + refused)
                    .hasMessageContaining("share guard");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantFixtures.asTenant(
                crew.items().organization().id(), connection -> TenantFixtures.update(connection,
                        "update record_share set grantee_id = ?, version = version + 1 where record_id = ?",
                        UUID.fromString(crew.roleTop()), record)))
                .as("nor does it move to another role").hasMessageContaining("share guard");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantFixtures.asTenant(
                crew.items().organization().id(), connection -> TenantFixtures.update(connection,
                        "insert into record_share (tenant_id, object_api_name, record_id, grantee_type, grantee_id, "
                                + "access_level, created_by, updated_by) values (?, 'Item__c', ?, 'TEAM', ?, 1, ?, ?)",
                        crew.items().organization().id().value(), record, UUID.randomUUID(),
                        UUID.fromString(crew.author().userId()), UUID.fromString(crew.author().userId()))))
                .as("a kind the table does not know").hasMessageContaining("record_share_grantee_kind_known");
    }

    // ---- the level, the kind, taking it back ----

    @Test
    void aRoleShareNeverGivesMoreThanTheLevelChosenAndNeverTheRightToDelete() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafA(), "READ");

        assertThat(change(crew, crew.leafA1().browser(), "by-reader").status()).as("read only").isEqualTo(403);
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafA(), "READ_WRITE");
        Response changed = change(crew, crew.leafA1().browser(), "by-editor");
        Response deleted = crew.leafA1().browser().request("DELETE", ITEM_RECORDS + "/" + crew.record(), null);

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(deleted.status()).as("a share never gives the right to delete").isEqualTo(403);
        assertThat(change(crew, crew.leafB().browser(), "by-other").status()).as("not their role")
                .isEqualTo(404);
        assertThat(decides(crew, crew.leafA2(), ObjectAction.UPDATE)).isTrue();
        assertThat(decides(crew, crew.leafA2(), ObjectAction.DELETE)).isFalse();
    }

    @Test
    void sharingTheSameRoleAgainChangesTheKindAndTheLevelAndNeverAddsASecondShare() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleMiddle(), "READ");
        assertThat(whoReads(crew)).containsExactly("middle");

        Response again = shareWith(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(),
                "READ_WRITE");
        Response list = crew.author().browser().get(sharesOf(crew.record()));

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].granteeType"))
                .containsExactly("ROLE_AND_SUBORDINATES");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].accessLevel")).containsExactly("READ_WRITE");
        assertThat(whoReads(crew)).containsExactly("middle", "leafA1", "leafA2", "leafB");
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleMiddle(), "READ");
        assertThat(whoReads(crew)).as("and back again").containsExactly("middle");
    }

    @Test
    void takingAShareToARoleBackWithTheRoleIdentifierEndsItAtTheNextQuestion() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(), "READ");
        assertThat(whoReads(crew)).contains("leafB");

        Response taken = crew.author().browser().request("DELETE", sharesOf(crew.record()) + "/" + crew.roleMiddle(),
                null);
        Response again = crew.author().browser().request("DELETE", sharesOf(crew.record()) + "/" + crew.roleMiddle(),
                null);

        assertThat(taken.status()).isEqualTo(204);
        assertThat(again.status()).as("a second time there is nothing to take back").isEqualTo(404);
        assertThat(whoReads(crew)).isEmpty();
    }

    // ---- people and roles that move ----

    @Test
    void aMemberMovedOutOfTheRoleLosesTheRecordAndOneMovedInGainsItAtTheNextQuestion() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafA(), "READ");
        assertThat(whoReads(crew)).containsExactly("leafA1", "leafA2");

        putInRole(crew.items().admin(), crew.leafA1(), null);
        putInRole(crew.items().admin(), crew.outsider(), crew.roleLeafA());

        assertThat(whoReads(crew)).containsExactly("leafA2", "outsider");
    }

    @Test
    void aRoleMovedInTheTreeChangesWhoAShareToItsSubordinatesReaches() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(), "READ");
        assertThat(whoReads(crew)).contains("leafA1", "leafA2");

        Response moved = crew.items().admin().request("PUT", ROLES + "/" + crew.roleLeafA(),
                "{\"name\":\"role-leaf-a\",\"parentId\":\"" + crew.roleTop() + "\"}");
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(whoReads(crew)).as("role-leaf-a is now beside role-middle, not below it")
                .containsExactly("middle", "leafB");
        Response back = crew.items().admin().request("PUT", ROLES + "/" + crew.roleLeafA(),
                "{\"name\":\"role-leaf-a\",\"parentId\":\"" + crew.roleMiddle() + "\"}");

        assertThat(back.status()).isEqualTo(200);
        assertThat(whoReads(crew)).containsExactly("middle", "leafA1", "leafA2", "leafB");
    }

    @Test
    void removingARoleOrAGroupEndsTheSharesToItInTheSameTransactionAndTheAuditSaysHowMany() {
        Crew crew = crew();
        TestBrowser admin = crew.items().admin();
        String spare = role(admin, "role-spare", null);
        String friends = group(admin, "group-a", crew.outsider());
        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", spare, "READ");
        shared(crew.author().browser(), crew.record(), "GROUP", friends, "READ");
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafB(), "READ");
        assertThat(whoReads(crew)).contains("outsider", "leafB");

        Response roleGone = admin.request("DELETE", ROLES + "/" + spare, null);
        Response groupGone = admin.request("DELETE", GROUPS + "/" + friends, null);

        assertThat(roleGone.status()).as(roleGone.body()).isEqualTo(204);
        assertThat(groupGone.status()).as(groupGone.body()).isEqualTo(204);
        assertThat(JsonPath.<List<String>>read(crew.author().browser().get(sharesOf(crew.record())).body(),
                "$.data[*].granteeType")).as("only the share to a role that still exists is left")
                .containsExactly("ROLE");
        assertThat(whoReads(crew)).containsExactly("leafB");
        UUID tenant = crew.items().organization().id().value();
        assertThat(owner("select attributes->>'shares_ended' from audit_record where event_type = ? "
                + "and context_tenant_id = ?", "access.role.deleted", tenant)).containsExactly("1");
        assertThat(owner("select attributes->>'shares_ended' from audit_record where event_type = ? "
                + "and context_tenant_id = ?", "access.group.deleted", tenant)).containsExactly("1");
    }

    @Test
    void aRoleThatHoldsSharesCanStillNotBeRemovedWhileItHasMembers() {
        Crew crew = crew();
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafB(), "READ");

        Response refused = crew.items().admin().request("DELETE", ROLES + "/" + crew.roleLeafB(), null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(whoReads(crew)).containsExactly("leafB");
    }

    // ---- what the list of shares says ----

    @Test
    void theListNamesARoleOrAGroupByItsNameAndAPersonByNothingAndFollowsARename() {
        Crew crew = crew();
        String friends = group(crew.items().admin(), "group-a");
        shared(crew.author().browser(), crew.record(), "ROLE", crew.roleLeafA(), "READ");
        shared(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(), "READ");
        shared(crew.author().browser(), crew.record(), "GROUP", friends, "READ");
        shared(crew.author().browser(), crew.record(), "USER", crew.outsider().userId(), "READ");

        String list = crew.author().browser().get(sharesOf(crew.record())).body();

        assertThat(JsonPath.<List<String>>read(list, "$.data[?(@.granteeType=='ROLE')].granteeLabel"))
                .containsExactly("role-leaf-a");
        assertThat(JsonPath.<List<String>>read(list, "$.data[?(@.granteeType=='ROLE_AND_SUBORDINATES')].granteeLabel"))
                .containsExactly("role-middle");
        assertThat(JsonPath.<List<String>>read(list, "$.data[?(@.granteeType=='GROUP')].granteeLabel"))
                .containsExactly("group-a");
        assertThat(JsonPath.<List<Object>>read(list, "$.data[?(@.granteeType=='USER')].granteeLabel"))
                .as("a person is named through the labels of the owner field, not by this list")
                .allMatch(java.util.Objects::isNull);
        crew.items().admin().request("PUT", ROLES + "/" + crew.roleLeafA(),
                "{\"name\":\"role-renamed\",\"parentId\":\"" + crew.roleMiddle() + "\"}");
        assertThat(JsonPath.<List<String>>read(crew.author().browser().get(sharesOf(crew.record())).body(),
                "$.data[?(@.granteeType=='ROLE')].granteeLabel")).containsExactly("role-renamed");
    }

    // ---- what is refused ----

    @Test
    void aRoleOfAnotherOrganizationOrOneThatDoesNotExistIsRefusedWithTheSameWords() {
        Crew crew = crew();
        Items theirs = items();
        String theirRole = role(theirs.admin(), "role-top", null);

        Response foreign = shareWith(crew.author().browser(), crew.record(), "ROLE", theirRole, "READ");
        Response unknown = shareWith(crew.author().browser(), crew.record(), "ROLE_AND_SUBORDINATES", missing(),
                "READ");
        Response kind = shareWith(crew.author().browser(), crew.record(), "TEAM", crew.roleTop(), "READ");

        assertThat(foreign.status()).as(foreign.body()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(JsonPath.<Object>read(foreign.body(), "$.error.fields.granteeId").toString())
                .contains("Is not a role of this organization.")
                .isEqualTo(JsonPath.<Object>read(unknown.body(), "$.error.fields.granteeId").toString());
        assertThat(kind.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(crew.author().browser().get(sharesOf(crew.record())).body(), "$.data"))
                .isEmpty();
    }

    @Test
    void aShareToARoleIsAllowedOnlyToWhoMayChangeTheRecordAndAnotherOrganizationOrAForgedHeaderChangesNothing() {
        Crew crew = crew();
        share(crew.items().admin(), "PUBLIC_READ", false);
        Items theirs = items();
        String[] forged = {"X-Tenant-Id", crew.items().organization().id().value().toString(), "X-Forwarded-Host",
            crew.items().organization().host()};
        String payload = body("ROLE", crew.roleTop(), "READ");

        assertThat(crew.outsider().browser().request("PUT", sharesOf(crew.record()), payload).status())
                .as("can read the record, may not change it").isEqualTo(403);
        assertThat(theirs.admin().request("PUT", sharesOf(crew.record()), payload).status()).as("another organization")
                .isEqualTo(404);
        assertThat(theirs.admin().request("PUT", sharesOf(crew.record()), payload, forged).status())
                .as("a forged header").isEqualTo(404);
        assertThat(new TestBrowser(port, crew.items().organization().host())
                .request("PUT", sharesOf(crew.record()), payload).status()).as("not signed in").isEqualTo(401);
        assertThat(JsonPath.<List<String>>read(crew.author().browser().get(sharesOf(crew.record())).body(), "$.data"))
                .isEmpty();
    }

    @Test
    void aRoleShareCountsTowardsTheMostSharesARecordCanHaveAndChangingOneDoesNot() {
        Crew crew = crew();
        try (TestApplication small = TestApplication.start("platform.data.sharing.max-shares-per-record=2")) {
            TestBrowser owner = TestOrganizations.signedIn(small.port(), crew.items().organization().host(),
                    crew.author().member().person());

            shared(owner, crew.record(), "ROLE", crew.roleTop(), "READ");
            shared(owner, crew.record(), "ROLE_AND_SUBORDINATES", crew.roleMiddle(), "READ");
            Response third = shareWith(owner, crew.record(), "ROLE", crew.roleLeafA(), "READ");
            Response changed = shareWith(owner, crew.record(), "ROLE_AND_SUBORDINATES", crew.roleTop(), "READ_WRITE");

            assertThat(third.status()).as(third.body()).isEqualTo(409);
            assertThat(changed.status()).as("changing a share is not another share").isEqualTo(200);
        }
    }

    // ---- the roles and groups to choose from ----

    private static String targets(String record, String query) {
        return sharesOf(record).replace("/shares", "/share-targets") + "?" + query;
    }

    @Test
    void whoMayShareARecordIsOfferedTheRolesAndGroupsByNameWithNothingAboutThePeopleInThem() {
        Crew crew = crew();
        group(crew.items().admin(), "group-a", crew.outsider());
        group(crew.items().admin(), "group-b");

        Response roles = crew.author().browser().get(targets(crew.record(), "kind=ROLE"));
        Response groups = crew.author().browser().get(targets(crew.record(), "kind=GROUP"));
        Response narrowed = crew.author().browser().get(targets(crew.record(), "kind=ROLE&search=LEAF"));

        assertThat(roles.status()).as(roles.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(roles.body(), "$.data[*].label")).containsExactly("role-leaf-a",
                "role-leaf-b", "role-middle", "role-top");
        assertThat(JsonPath.<Map<String, Object>>read(roles.body(), "$.data[0]").keySet())
                .containsExactlyInAnyOrder("id", "label");
        assertThat(JsonPath.<List<String>>read(groups.body(), "$.data[*].label")).containsExactly("group-a",
                "group-b");
        assertThat(JsonPath.<List<String>>read(narrowed.body(), "$.data[*].label")).containsExactly("role-leaf-a",
                "role-leaf-b");
        assertThat(roles.body() + groups.body()).doesNotContain(crew.outsider().userId());
    }

    @Test
    void theListOfRolesToChooseFromIsOnePageAndAnEmptySearchOrOneThatMatchesNothingIsAnEmptyList() {
        Crew crew = crew();
        for (int number = 10; number < 40; number++) {
            role(crew.items().admin(), "bulk-role-" + number, null);
        }

        Response page = crew.author().browser().get(targets(crew.record(), "kind=ROLE"));
        Response nothing = crew.author().browser().get(targets(crew.record(), "kind=ROLE&search=zzz-none"));

        assertThat(JsonPath.<List<String>>read(page.body(), "$.data[*].id")).hasSize(RecordSharing.MAX_TARGETS);
        assertThat(JsonPath.<List<String>>read(nothing.body(), "$.data")).isEmpty();
    }

    @Test
    void aKindThatIsNotRoleOrGroupAMissingKindAndATooLongSearchAreRefused() {
        Crew crew = crew();

        assertThat(crew.author().browser().get(targets(crew.record(), "kind=USER")).status()).isEqualTo(400);
        assertThat(crew.author().browser().get(targets(crew.record(), "kind=ROLE_AND_SUBORDINATES")).status())
                .isEqualTo(400);
        assertThat(crew.author().browser().get(targets(crew.record(), "kind=TEAM")).status()).isEqualTo(400);
        assertThat(crew.author().browser().get(targets(crew.record(), "search=x")).status()).isEqualTo(400);
        assertThat(crew.author().browser().get(targets(crew.record(), "kind=ROLE&search=" + "x".repeat(81))).status())
                .isEqualTo(400);
    }

    @Test
    void theRolesToChooseFromAreOfferedOnlyToWhoMayShareTheRecordAndNeverAcrossOrganizationsOrWithAForgedHeader() {
        Crew crew = crew();
        share(crew.items().admin(), "PUBLIC_READ", false);
        Items theirs = items();
        String[] forged = {"X-Tenant-Id", crew.items().organization().id().value().toString(), "X-Forwarded-Host",
            crew.items().organization().host()};
        String path = targets(crew.record(), "kind=ROLE");

        assertThat(crew.author().browser().get(path).status()).as("the owner").isEqualTo(200);
        assertThat(crew.outsider().browser().get(path).status()).as("can read, may not change").isEqualTo(403);
        assertThat(theirs.admin().get(path).status()).as("another organization").isEqualTo(404);
        assertThat(theirs.admin().request("GET", path, null, forged).status()).as("a forged header").isEqualTo(404);
        assertThat(new TestBrowser(port, crew.items().organization().host()).get(path).status()).as("not signed in")
                .isEqualTo(401);
        share(crew.items().admin(), "PRIVATE", false);
        assertThat(crew.leafB().browser().get(path).status()).as("cannot reach the record: the same as no record")
                .isEqualTo(404);
        assertThat(crew.leafB().browser().get(targets(missing(), "kind=ROLE")).status()).isEqualTo(404);
    }

    @Test
    void aRoleOrGroupOfAnotherOrganizationIsNeverOfferedAndNeverInTheList() {
        Crew crew = crew();
        Items theirs = items();
        role(theirs.admin(), "role-of-theirs", null);
        group(theirs.admin(), "group-of-theirs");

        Response roles = crew.author().browser().get(targets(crew.record(), "kind=ROLE"));
        Response groups = crew.author().browser().get(targets(crew.record(), "kind=GROUP"));

        assertThat(roles.body() + groups.body()).doesNotContain("of-theirs");
    }
}
