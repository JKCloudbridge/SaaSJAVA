package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Record-level security on the write side (Sprint 17A, ADR-0125). A change of a record is answered exactly as the
 * single read of it: not found for a member who cannot read it, forbidden for a member who can read it and may not
 * change it. Through the real API, with a manager above two workers and an outsider; the decision API of the security
 * module is the oracle that the list, the read, the change and the removal are held to.
 */
@PlatformIntegrationTest
class RecordWriteSecurityIT extends RecordSecuritySupport {

    private static final String INVALID = "{\"qty__c\":\"not a number\"}";

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    /** A manager above two workers, an outsider and one record of each worker and of the outsider. */
    private record Crew(Items items, Person manager, Person alice, Person carol, Person dave, String workers,
            String byAlice, String byCarol, String byDave) {

        List<String> records() {
            return List.of(byAlice, byCarol, byDave);
        }

        List<Person> people() {
            return List.of(manager, alice, carol, dave);
        }
    }

    private Crew crew() {
        Items items = items();
        TestBrowser admin = items.admin();
        Person manager = worker(items.organization(), admin);
        Person alice = worker(items.organization(), admin);
        Person carol = worker(items.organization(), admin);
        Person dave = worker(items.organization(), admin);
        String boss = role(admin, "Boss", null);
        String workers = role(admin, "Worker", boss);
        putInRole(admin, manager, boss);
        putInRole(admin, alice, workers);
        putInRole(admin, carol, workers);
        return new Crew(items, manager, alice, carol, dave, workers, newItem(alice.browser(), "alice-1"),
                newItem(carol.browser(), "carol-1"), newItem(dave.browser(), "dave-1"));
    }

    // ---- helpers ----

    private static String change(String name) {
        return "{\"fields\":{\"name__c\":\"" + name + "\"}}";
    }

    private static Response alter(TestBrowser browser, String record, long version, String fieldsJson) {
        return browser.request("PATCH", ITEM_RECORDS + "/" + record,
                "{\"version\":" + version + ",\"fields\":" + fieldsJson + "}");
    }

    private static Response remove(TestBrowser browser, String record) {
        return browser.request("DELETE", ITEM_RECORDS + "/" + record, null);
    }

    private static Response restore(TestBrowser browser, String record) {
        return browser.postJson(ITEM_BIN + "/" + record + "/restore", "{}");
    }

    /** The version of a record, read by the administrator (who holds modify-all). */
    private static long versionOf(Crew crew, String record) {
        Response read = crew.items().admin().get(ITEM_RECORDS + "/" + record);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return ((Number) JsonPath.read(read.body(), "$.data.version")).longValue();
    }

    private static String nameOf(Crew crew, String record) {
        return JsonPath.read(crew.items().admin().get(ITEM_RECORDS + "/" + record).body(),
                "$.data.fields.name__c");
    }

    private static String message(Response response) {
        return JsonPath.read(response.body(), "$.error.message");
    }

    private boolean decides(Crew crew, Person person, String record, ObjectAction action) {
        TenantContext context = new TenantContext(crew.items().organization().id(),
                person.member().person().user().id(), person.member().membership());
        return contexts.call(context, () -> decisions.can(person.member().membership(), "Item__c", action,
                new RecordRef(UUID.fromString(record))).allowed());
    }

    // ---- the two answers ----

    @Test
    void aMemberWhoCannotReadARecordIsToldItDoesNotExistWhateverTheyTryToDoWithIt() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        TestBrowser dave = crew.dave().browser();
        long version = versionOf(crew, crew.byAlice());

        Response read = dave.get(ITEM_RECORDS + "/" + crew.byAlice());
        Response changed = alter(dave, crew.byAlice(), version, "{\"name__c\":\"mine now\"}");
        Response judged = alter(dave, crew.byAlice(), version, INVALID);
        Response removed = remove(dave, crew.byAlice());

        assertThat(read.status()).isEqualTo(404);
        assertThat(changed.status()).as(changed.body()).isEqualTo(404);
        assertThat(judged.status()).as("an invalid body is not judged before the record is: " + judged.body())
                .isEqualTo(404);
        assertThat(removed.status()).as(removed.body()).isEqualTo(404);
        for (Response answer : List.of(changed, judged, removed)) {
            assertThat(message(answer)).as("the words of the read").isEqualTo(message(read));
        }
        assertThat(nameOf(crew, crew.byAlice())).isEqualTo("alice-1");
        assertThat(versionOf(crew, crew.byAlice())).as("nothing was written").isEqualTo(version);
    }

    @Test
    void aMemberWhoCanReadARecordButNotChangeItIsForbiddenAndNothingIsJudgedFirst() {
        Crew crew = crew();
        share(crew.items().admin(), "PUBLIC_READ", true);
        TestBrowser dave = crew.dave().browser();
        long version = versionOf(crew, crew.byAlice());

        Response read = dave.get(ITEM_RECORDS + "/" + crew.byAlice());
        Response changed = alter(dave, crew.byAlice(), version, "{\"name__c\":\"mine now\"}");
        Response judged = alter(dave, crew.byAlice(), version, INVALID);
        Response removed = remove(dave, crew.byAlice());

        assertThat(read.status()).isEqualTo(200);
        assertThat(changed.status()).as(changed.body()).isEqualTo(403);
        assertThat(judged.status()).as("forbidden before a value is judged: " + judged.body()).isEqualTo(403);
        assertThat(removed.status()).as(removed.body()).isEqualTo(403);
        assertThat(nameOf(crew, crew.byAlice())).isEqualTo("alice-1");
        assertThat(versionOf(crew, crew.byAlice())).isEqualTo(version);
        assertThat(alter(dave, crew.byDave(), versionOf(crew, crew.byDave()), "{\"name__c\":\"dave-2\"}").status())
                .as("their own record is theirs to change").isEqualTo(200);
        assertThat(remove(dave, crew.byDave()).status()).as("and to remove").isEqualTo(204);
    }

    @Test
    void theRoleHierarchyGivesTheManagerReadAndNothingMore() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        TestBrowser manager = crew.manager().browser();
        long version = versionOf(crew, crew.byAlice());

        assertThat(manager.get(ITEM_RECORDS + "/" + crew.byAlice()).status()).isEqualTo(200);
        assertThat(alter(manager, crew.byAlice(), version, "{\"name__c\":\"boss was here\"}").status())
                .isEqualTo(403);
        assertThat(remove(manager, crew.byAlice()).status()).isEqualTo(403);
        assertThat(manager.get(ITEM_RECORDS + "/" + crew.byDave()).status()).as("outside the hierarchy")
                .isEqualTo(404);
        assertThat(alter(manager, crew.byDave(), versionOf(crew, crew.byDave()), "{\"name__c\":\"x\"}").status())
                .isEqualTo(404);
        assertThat(nameOf(crew, crew.byAlice())).isEqualTo("alice-1");
    }

    @Test
    void aShareGivesReadOrReadAndWriteAndNeverTheRightToDelete() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        TestBrowser alice = crew.alice().browser();
        TestBrowser dave = crew.dave().browser();
        String shares = ITEM_RECORDS + "/" + crew.byAlice() + "/shares";
        assertThat(alter(dave, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"x\"}").status())
                .isEqualTo(404);

        alice.request("PUT", shares, "{\"granteeType\":\"USER\",\"granteeId\":\"" + crew.dave().userId()
                + "\",\"accessLevel\":\"READ\"}");
        assertThat(alter(dave, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"x\"}").status())
                .as("shared to read").isEqualTo(403);

        alice.request("PUT", shares, "{\"granteeType\":\"USER\",\"granteeId\":\"" + crew.dave().userId()
                + "\",\"accessLevel\":\"READ_WRITE\"}");
        assertThat(alter(dave, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"shared\"}").status())
                .as("shared to write").isEqualTo(200);
        assertThat(remove(dave, crew.byAlice()).status()).as("a share never gives delete").isEqualTo(403);

        alice.request("DELETE", shares + "/" + crew.dave().userId(), null);
        assertThat(alter(dave, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"y\"}").status())
                .as("taken back, seen at the next question").isEqualTo(404);
    }

    @Test
    void modifyAllLetsAnAdministratorChangeAndRemoveEveryRecordOfAPrivateObject() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", false);
        TestBrowser admin = crew.items().admin();

        assertThat(alter(admin, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"admin\"}")
                .status()).isEqualTo(200);
        assertThat(remove(admin, crew.byCarol()).status()).isEqualTo(204);
        assertThat(restore(admin, crew.byCarol()).status()).isEqualTo(200);
    }

    @Test
    void anObjectNobodySetUpKeepsTheBehaviourOfBeforeSprint17A() {
        Crew crew = crew();
        TestBrowser dave = crew.dave().browser();

        assertThat(alter(dave, crew.byAlice(), versionOf(crew, crew.byAlice()), "{\"name__c\":\"open\"}").status())
                .isEqualTo(200);
        assertThat(remove(dave, crew.byCarol()).status()).isEqualTo(204);
        assertThat(restore(dave, crew.byCarol()).status()).isEqualTo(200);
    }

    @Test
    void aCreateIsOwnedByTheCallerAndTheCallerAlwaysReadsItBack() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", false);

        Response created = crew.dave().browser().postJson(ITEM_RECORDS, change("fresh"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.ownerId")).isEqualTo(crew.dave().userId());
        assertThat(crew.dave().browser().get(ITEM_RECORDS + "/" + JsonPath.read(created.body(), "$.data.id"))
                .status()).isEqualTo(200);
        assertThat(crew.alice().browser().get(ITEM_RECORDS + "/" + JsonPath.read(created.body(), "$.data.id"))
                .status()).isEqualTo(404);
    }

    // ---- the recycle bin ----

    @Test
    void theRecycleBinShowsAndReturnsWhatTheMemberMayBringBackAndNothingElse() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        assertThat(remove(crew.alice().browser(), crew.byAlice()).status()).isEqualTo(204);

        List<String> hers = JsonPath.read(crew.alice().browser().get(ITEM_BIN).body(), "$.data[*].id");
        List<String> his = JsonPath.read(crew.dave().browser().get(ITEM_BIN).body(), "$.data[*].id");
        List<String> theirs = JsonPath.read(crew.manager().browser().get(ITEM_BIN).body(), "$.data[*].id");
        List<String> everything = JsonPath.read(crew.items().admin().get(ITEM_BIN).body(), "$.data[*].id");

        assertThat(hers).containsExactly(crew.byAlice());
        assertThat(his).as("the outsider sees nothing of it").isEmpty();
        assertThat(theirs).as("a manager may read the record but not bring it back").isEmpty();
        assertThat(everything).containsExactly(crew.byAlice());
        assertThat(restore(crew.dave().browser(), crew.byAlice()).status()).isEqualTo(404);
        assertThat(crew.dave().browser().request("DELETE", ITEM_BIN + "/" + crew.byAlice(), null).status())
                .isEqualTo(404);
        assertThat(restore(crew.manager().browser(), crew.byAlice()).status()).isEqualTo(403);
        assertThat(crew.manager().browser().request("DELETE", ITEM_BIN + "/" + crew.byAlice(), null).status())
                .isEqualTo(403);
        assertThat(restore(crew.alice().browser(), crew.byAlice()).status()).isEqualTo(200);
        assertThat(crew.alice().browser().get(ITEM_RECORDS + "/" + crew.byAlice()).status()).isEqualTo(200);
    }

    @Test
    void aRecordInTheBinIsRemovedForGoodOnlyByWhoMayDeleteIt() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        remove(crew.alice().browser(), crew.byAlice());

        assertThat(crew.dave().browser().request("DELETE", ITEM_BIN + "/" + crew.byAlice(), null).status())
                .isEqualTo(404);
        assertThat(crew.alice().browser().request("DELETE", ITEM_BIN + "/" + crew.byAlice(), null).status())
                .isEqualTo(204);
        assertThat(crew.items().admin().get(ITEM_BIN).body()).doesNotContain(crew.byAlice());
    }

    // ---- bulk ----

    @Test
    void aBulkCallJudgesEveryRecordLikeASingleCall() {
        Crew crew = crew();
        share(crew.items().admin(), "PRIVATE", true);
        long version = versionOf(crew, crew.byAlice());
        String update = "{\"operation\":\"UPDATE\",\"allOrNone\":false,\"records\":[{\"id\":\"%s\",\"version\":%d,"
                + "\"fields\":{\"name__c\":\"bulk\"}}]}";
        String delete = "{\"operation\":\"DELETE\",\"allOrNone\":false,\"records\":[{\"id\":\"%s\"}]}";

        Response unseen = crew.dave().browser().postJson(ITEM_RECORDS + "/bulk",
                update.formatted(crew.byAlice(), version));
        Response readOnly = crew.manager().browser().postJson(ITEM_RECORDS + "/bulk",
                update.formatted(crew.byAlice(), version));
        Response readOnlyDelete = crew.manager().browser().postJson(ITEM_RECORDS + "/bulk",
                delete.formatted(crew.byAlice()));
        Response own = crew.alice().browser().postJson(ITEM_RECORDS + "/bulk",
                update.formatted(crew.byAlice(), version));

        assertThat(JsonPath.<String>read(unseen.body(), "$.data.results[0].code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<String>read(readOnly.body(), "$.data.results[0].code")).isEqualTo("FORBIDDEN");
        assertThat(JsonPath.<String>read(readOnlyDelete.body(), "$.data.results[0].code")).isEqualTo("FORBIDDEN");
        assertThat(JsonPath.<String>read(own.body(), "$.data.results[0].status")).isEqualTo("UPDATED");
    }

    // ---- another organization, a forged header ----

    @Test
    void anotherOrganizationCannotChangeOrRemoveARecordAndAForgedHeaderChangesNothing() {
        Crew mine = crew();
        Items theirs = items();
        share(mine.items().admin(), "PRIVATE", true);
        String[] forged = {"X-Tenant-Id", mine.items().organization().id().value().toString(), "X-Forwarded-Host",
            mine.items().organization().host()};
        long version = versionOf(mine, mine.byAlice());

        assertThat(alter(theirs.admin(), mine.byAlice(), version, "{\"name__c\":\"x\"}").status()).isEqualTo(404);
        assertThat(remove(theirs.admin(), mine.byAlice()).status()).isEqualTo(404);
        assertThat(theirs.admin().request("PATCH", ITEM_RECORDS + "/" + mine.byAlice(),
                "{\"version\":" + version + ",\"fields\":{\"name__c\":\"x\"}}", forged).status()).isEqualTo(404);
        assertThat(theirs.admin().request("DELETE", ITEM_RECORDS + "/" + mine.byAlice(), null, forged).status())
                .isEqualTo(404);
        assertThat(nameOf(mine, mine.byAlice())).isEqualTo("alice-1");
    }

    @Test
    void anUnauthenticatedCallerChangesNothing() {
        Crew crew = crew();
        TestBrowser nobody = new TestBrowser(port, crew.items().organization().host());

        assertThat(nobody.request("PATCH", ITEM_RECORDS + "/" + crew.byAlice(), "{\"version\":0,\"fields\":{}}")
                .status()).isEqualTo(401);
        assertThat(nobody.request("DELETE", ITEM_RECORDS + "/" + crew.byAlice(), null).status()).isEqualTo(401);
    }

    // ---- the matrix ----

    /**
     * For every sharing configuration, every member and every record, the four answers agree: the list and the single
     * read say whether the member can read it, the change says what the decision API says about editing, the removal
     * what it says about deleting. The configuration grows from the strictest to the loosest, with a rule and a share
     * on the way, and the same people and records go through all of it.
     */
    @Test
    void aListAReadAChangeAndARemovalAgreeForEveryMemberAndRecordInEverySharingConfiguration() {
        Crew crew = crew();
        TestBrowser admin = crew.items().admin();
        String friends = group(admin, "friends", crew.dave());

        agree(crew, "private, hierarchy off", () -> share(admin, "PRIVATE", false));
        agree(crew, "private, hierarchy on", () -> share(admin, "PRIVATE", true));
        agree(crew, "a share to read", () -> crew.carol().browser().request("PUT",
                ITEM_RECORDS + "/" + crew.byCarol() + "/shares", "{\"granteeType\":\"USER\",\"granteeId\":\""
                        + crew.dave().userId() + "\",\"accessLevel\":\"READ\"}"));
        agree(crew, "a share to write", () -> crew.carol().browser().request("PUT",
                ITEM_RECORDS + "/" + crew.byCarol() + "/shares", "{\"granteeType\":\"USER\",\"granteeId\":\""
                        + crew.dave().userId() + "\",\"accessLevel\":\"READ_WRITE\"}"));
        agree(crew, "a rule that lets a group write what the workers own", () -> rule(admin,
                "{\"object\":\"Item__c\",\"name\":\"workers\",\"kind\":\"OWNER\",\"sourceType\":\"ROLE\","
                        + "\"sourceId\":\"" + crew.workers() + "\",\"targetType\":\"GROUP\",\"targetId\":\""
                        + friends + "\",\"accessLevel\":\"READ_WRITE\"}"));
        agree(crew, "public read", () -> share(admin, "PUBLIC_READ", true));
        agree(crew, "public read and write", () -> share(admin, "PUBLIC_READ_WRITE", true));
    }

    private void agree(Crew crew, String configuration, Runnable configure) {
        configure.run();
        for (Person person : crew.people()) {
            List<String> list = listed(person.browser());
            for (String record : crew.records()) {
                String where = configuration + ", " + person.userId() + ", " + record;
                boolean read = decides(crew, person, record, ObjectAction.READ);
                boolean edit = decides(crew, person, record, ObjectAction.UPDATE);
                boolean delete = decides(crew, person, record, ObjectAction.DELETE);
                assertThat(list.contains(record)).as("list: " + where).isEqualTo(read);
                assertThat(reads(person.browser(), record)).as("read: " + where).isEqualTo(read);

                Response changed = alter(person.browser(), record, versionOf(crew, record),
                        "{\"name__c\":\"" + UUID.randomUUID() + "\"}");
                assertThat(changed.status()).as("change: " + where + " " + changed.body())
                        .isEqualTo(read ? (edit ? 200 : 403) : 404);

                Response removed = remove(person.browser(), record);
                assertThat(removed.status()).as("removal: " + where + " " + removed.body())
                        .isEqualTo(read ? (delete ? 204 : 403) : 404);
                if (removed.status() == 204) {
                    assertThat(restore(crew.items().admin(), record).status()).as("put back: " + where)
                            .isEqualTo(200);
                }
            }
        }
    }
}
