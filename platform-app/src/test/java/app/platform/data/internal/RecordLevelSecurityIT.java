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
 * The exit criterion of Sprint 17 (ADR-0115 to ADR-0119): list queries and single-record access give identical answers
 * for the same member, and a change of how records are shared is seen at the next question. Through the real API, with
 * four people in a small organization: a manager above two workers, and an outsider.
 */
@PlatformIntegrationTest
class RecordLevelSecurityIT extends RecordSecuritySupport {

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    /**
     * The setting in which every test starts: a manager, two workers, an outsider, one record each (but
     * the manager).
     */
    private record Team(Items items, Person manager, Person alice, Person carol, Person dave, String boss,
            String worker, String byAlice, String byCarol, String byDave) {
    }

    private Team team() {
        Items items = items();
        TestBrowser admin = items.admin();
        Person manager = worker(items.organization(), admin);
        Person alice = worker(items.organization(), admin);
        Person carol = worker(items.organization(), admin);
        Person dave = worker(items.organization(), admin);
        String boss = role(admin, "Boss", null);
        String workerRole = role(admin, "Worker", boss);
        putInRole(admin, manager, boss);
        putInRole(admin, alice, workerRole);
        putInRole(admin, carol, workerRole);
        String byAlice = newItem(alice.browser(), "alice-1");
        String byCarol = newItem(carol.browser(), "carol-1");
        String byDave = newItem(dave.browser(), "dave-1");
        return new Team(items, manager, alice, carol, dave, boss, workerRole, byAlice, byCarol, byDave);
    }

    /** What the decision API says for one record: the single-record answer of the security module. */
    private boolean decides(Team team, Person person, String record) {
        TenantContext context = new TenantContext(team.items().organization().id(),
                person.member().person().user().id(), person.member().membership());
        return contexts.call(context, () -> decisions.can(person.member().membership(), "Item__c",
                ObjectAction.READ, new RecordRef(UUID.fromString(record))).allowed());
    }

    /** The four answers (list, query, single read, decision) for every person and record agree; returns the list. */
    private List<String> agree(Team team, Person person) {
        List<String> list = listed(person.browser());
        List<String> query = queried(person.browser());
        assertThat(query).as("a query returns what a list returns").containsExactlyInAnyOrderElementsOf(list);
        for (String record : List.of(team.byAlice(), team.byCarol(), team.byDave())) {
            boolean inList = list.contains(record);
            assertThat(reads(person.browser(), record)).as("single read of " + record).isEqualTo(inList);
            assertThat(decides(team, person, record)).as("decision for " + record).isEqualTo(inList);
        }
        return list;
    }

    @Test
    void anObjectNobodySetUpKeepsTheBehaviourOfBeforeSprint17() {
        Team team = team();

        for (Person person : List.of(team.manager(), team.alice(), team.carol(), team.dave())) {
            assertThat(agree(team, person)).containsExactlyInAnyOrder(team.byAlice(), team.byCarol(), team.byDave());
        }
    }

    @Test
    void aPrivateObjectShowsEachWorkerTheirOwnRecordsAndTheManagerTheirs() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);

        assertThat(agree(team, team.alice())).containsExactly(team.byAlice());
        assertThat(agree(team, team.carol())).containsExactly(team.byCarol());
        assertThat(agree(team, team.dave())).containsExactly(team.byDave());
        assertThat(agree(team, team.manager())).containsExactlyInAnyOrder(team.byAlice(), team.byCarol());
        assertThat(agree(team, new Person(team.items().organization().admin(), team.items().admin())))
                .as("the administrator holds modify-all").hasSize(3);
    }

    @Test
    void withTheHierarchySwitchedOffTheManagerReadsOnlyWhatIsTheirs() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", false);
        String own = newItem(team.manager().browser(), "boss-1");

        assertThat(agree(team, team.manager())).containsExactly(own);
    }

    @Test
    void aPersonOutsideTheHierarchyNeverReadsWhatTheManagerOwnsAndIsNotAnOwnerEither() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        String own = newItem(team.manager().browser(), "boss-1");

        assertThat(agree(team, team.alice())).doesNotContain(own);
        assertThat(agree(team, team.dave())).doesNotContain(own);
    }

    @Test
    void publicReadLetsEveryoneReadEveryRecord() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        share(team.items().admin(), "PUBLIC_READ", true);

        assertThat(agree(team, team.dave())).containsExactlyInAnyOrder(team.byAlice(), team.byCarol(), team.byDave());
    }

    @Test
    void changingTheDefaultTakesEffectAtTheNextQuestionAndRewritesNothing() {
        Team team = team();
        assertThat(listed(team.dave().browser())).hasSize(3);

        share(team.items().admin(), "PRIVATE", true);
        assertThat(listed(team.dave().browser())).containsExactly(team.byDave());
        share(team.items().admin(), "PUBLIC_READ_WRITE", true);

        assertThat(listed(team.dave().browser())).hasSize(3);
    }

    @Test
    void aRecordSharedWithOnePersonIsReadByThemAndOnlyThemUntilTheShareIsTakenBack() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);

        Response shared = team.alice().browser().request("PUT", ITEM_RECORDS + "/" + team.byAlice() + "/shares",
                "{\"granteeType\":\"USER\",\"granteeId\":\"" + team.dave().userId() + "\",\"accessLevel\":\"READ\"}");
        assertThat(shared.status()).as(shared.body()).isEqualTo(200);

        assertThat(agree(team, team.dave())).containsExactlyInAnyOrder(team.byDave(), team.byAlice());
        assertThat(agree(team, team.carol())).containsExactly(team.byCarol());
        Response taken = team.alice().browser().request("DELETE",
                ITEM_RECORDS + "/" + team.byAlice() + "/shares/" + team.dave().userId(), null);
        assertThat(taken.status()).isEqualTo(204);
        assertThat(agree(team, team.dave())).containsExactly(team.byDave());
    }

    @Test
    void aRecordSharedWithAGroupIsReadByEveryoneInIt() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        String friends = group(team.items().admin(), "friends", team.dave(), team.carol());

        Response shared = team.alice().browser().request("PUT", ITEM_RECORDS + "/" + team.byAlice() + "/shares",
                "{\"granteeType\":\"GROUP\",\"granteeId\":\"" + friends + "\",\"accessLevel\":\"READ\"}");
        assertThat(shared.status()).as(shared.body()).isEqualTo(200);

        assertThat(agree(team, team.dave())).contains(team.byAlice());
        assertThat(agree(team, team.carol())).contains(team.byAlice());
        assertThat(agree(team, team.manager())).contains(team.byAlice());
    }

    @Test
    void anOwnerRuleGivesTheRecordsOfARoleToAGroup() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        String readers = group(team.items().admin(), "readers", team.dave());

        String rule = rule(team.items().admin(), "{\"object\":\"Item__c\",\"name\":\"workers to readers\","
                + "\"kind\":\"OWNER\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + team.worker()
                + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + readers + "\",\"accessLevel\":\"READ\"}");

        assertThat(agree(team, team.dave())).containsExactlyInAnyOrder(team.byDave(), team.byAlice(), team.byCarol());
        Response removed = team.items().admin().request("DELETE", SHARING + "/rules/" + rule, null);
        assertThat(removed.status()).isEqualTo(204);
        assertThat(agree(team, team.dave())).containsExactly(team.byDave());
    }

    @Test
    void aRuleThatIsSwitchedOffGivesNothing() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        String readers = group(team.items().admin(), "readers", team.dave());
        String rule = rule(team.items().admin(), "{\"object\":\"Item__c\",\"name\":\"workers to readers\","
                + "\"kind\":\"OWNER\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + team.worker()
                + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + readers + "\",\"accessLevel\":\"READ\"}");
        long version = JsonPath.<Number>read(team.items().admin().get(SHARING + "/rules/" + rule).body(),
                "$.data.version").longValue();

        Response off = team.items().admin().request("PUT", SHARING + "/rules/" + rule, "{\"name\":\"workers to "
                + "readers\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + team.worker() + "\",\"targetType\":\"GROUP\","
                + "\"targetId\":\"" + readers + "\",\"accessLevel\":\"READ\",\"active\":false,\"version\":" + version
                + "}");

        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(agree(team, team.dave())).containsExactly(team.byDave());
    }

    @Test
    void aRuleByConditionGivesTheRecordsThatMeetItToAGroup() {
        Team team = team();
        TestBrowser admin = team.items().admin();
        assertThat(flag(admin, "stage", true).status()).isEqualTo(200);
        settle(team.items().organization(), admin);
        share(admin, "PRIVATE", true);
        String open = team.alice().browser().postJson(ITEM_RECORDS,
                "{\"fields\":{\"name__c\":\"open one\",\"stage__c\":\"a\"}}").body();
        String openId = JsonPath.read(open, "$.data.id");
        String readers = group(admin, "readers", team.dave());

        rule(admin, "{\"object\":\"Item__c\",\"name\":\"stage a\",\"kind\":\"CRITERIA\",\"criterion\":{\"op\":\"EQ\","
                + "\"field\":\"stage__c\",\"values\":[\"a\"]},\"targetType\":\"GROUP\",\"targetId\":\"" + readers
                + "\",\"accessLevel\":\"READ\"}");

        List<String> seen = agree(team, team.dave());
        assertThat(seen).contains(openId, team.byDave()).doesNotContain(team.byAlice(), team.byCarol());
    }

    @Test
    void viewAllReadsEveryRecordAndModifyAllToo() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        Holder viewer = holderWith(team.items().organization(), team.items().admin(), "Item__c",
                "[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]", "view-all");
        Holder modifier = holderWith(team.items().organization(), team.items().admin(), "Item__c",
                "[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]", "modify-all");

        Person viewing = new Person(viewer.member(), as(team.items().organization(), viewer.member()));
        Person modifying = new Person(modifier.member(), as(team.items().organization(), modifier.member()));

        assertThat(agree(team, viewing)).containsExactlyInAnyOrder(team.byAlice(), team.byCarol(), team.byDave());
        assertThat(agree(team, modifying)).containsExactlyInAnyOrder(team.byAlice(), team.byCarol(), team.byDave());
    }

    @Test
    void movingAPersonToAnotherRoleChangesWhatTheManagerReadsAtTheNextQuestion() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);
        assertThat(listed(team.manager().browser())).contains(team.byCarol());

        putInRole(team.items().admin(), team.carol(), null);

        assertThat(agree(team, team.manager())).containsExactly(team.byAlice());
    }

    @Test
    void aRecordTheMemberCannotSeeIsAnsweredLikeARecordThatDoesNotExist() {
        Team team = team();
        share(team.items().admin(), "PRIVATE", true);

        Response hidden = team.dave().browser().get(ITEM_RECORDS + "/" + team.byAlice());
        Response nothing = team.dave().browser().get(ITEM_RECORDS + "/" + missing());

        assertThat(hidden.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(hidden.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(nothing.body(), "$.error.message"));
    }

    @Test
    void aLinkToARecordTheMemberCannotSeeShowsTheIdentifierAlone() {
        Team team = team();
        TestBrowser admin = team.items().admin();
        Response field = admin.postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"title\",\"label\":\"title\",\"type\":\"TEXT\"}");
        assertThat(field.status()).as(field.body()).isEqualTo(201);
        String target = JsonPath.read(admin.postJson(TARGETS, "{\"fields\":{\"title__c\":\"secret target\"}}").body(),
                "$.data.id");
        Response linked = admin.postJson(ITEM_RECORDS,
                "{\"fields\":{\"name__c\":\"linked\",\"link__c\":\"" + target + "\"}}");
        assertThat(linked.status()).as(linked.body()).isEqualTo(201);
        Response matrix = admin.request("PUT", "/api/v1/profiles/" + team.dave().profileId() + "/data-access",
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\"]},{\"key\":\"Target__c\","
                        + "\"actions\":[\"read\"]}],\"fields\":[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]},"
                        + "{\"key\":\"Item__c.link__c\",\"actions\":[\"read\"]},{\"key\":\"Target__c.title__c\","
                        + "\"actions\":[\"read\"]}]}");
        assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        share(admin, "PRIVATE", true);
        share(admin, "PUBLIC_READ", true);
        Response openTarget = admin.request("PUT", SHARING + "/objects/Target__c", "{\"defaultAccess\":\"PRIVATE\","
                + "\"grantViaHierarchy\":true}");
        assertThat(openTarget.status()).as(openTarget.body()).isEqualTo(200);

        Response answer = team.dave().browser().postJson(ITEM_QUERY, "{\"fields\":[\"name__c\",\"link__c\"],"
                + "\"resolve\":[{\"field\":\"link__c\",\"fields\":[\"title__c\"]}],\"limit\":100}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.body()).doesNotContain("secret target");
        assertThat(answer.body()).contains(target);
    }
}
