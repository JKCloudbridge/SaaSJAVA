package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Saved list views (Sprint 19, ADR-0130): who sees and changes a view, the ability to share one, what is checked when
 * one is saved, the version a change must carry, and that a view is only data: it grants nothing, because opening one
 * is the ordinary query of whoever opens it.
 */
@PlatformIntegrationTest
class ListViewIT extends RecordSecuritySupport {

    private static final String VIEWS = ITEMS + "/views";
    private static final String PROFILES = "/api/v1/profiles";

    private static final String DEFINITION = "{\"filter\":{\"op\":\"EQ\",\"field\":\"name__c\",\"values\":[\"x\"]},"
            + "\"sort\":[{\"field\":\"name__c\",\"descending\":true}],\"columns\":[\"name__c\",\"qty__c\"]}";

    private static String body(String label, String scope, String definition) {
        return "{\"label\":\"" + label + "\",\"scope\":\"" + scope + "\",\"definition\":" + definition + "}";
    }

    private static Response save(TestBrowser browser, String label, String scope) {
        return browser.postJson(VIEWS, body(label, scope, DEFINITION));
    }

    private static String saved(TestBrowser browser, String label, String scope) {
        Response created = save(browser, label, scope);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }

    private static List<String> labels(TestBrowser browser) {
        Response list = browser.get(VIEWS);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].label");
    }

    private static long versionOfView(TestBrowser browser, String id) {
        Response list = browser.get(VIEWS);
        List<Number> versions = JsonPath.read(list.body(), "$.data[?(@.id=='" + id + "')].version");
        return versions.get(0).longValue();
    }

    private static String update(String label, String scope, long version) {
        return "{\"label\":\"" + label + "\",\"scope\":\"" + scope + "\",\"definition\":" + DEFINITION
                + ",\"version\":" + version + "}";
    }

    /** A member with the ability to share list views, and the permissions of a worker on {@code Item__c}. */
    private Member sharer(Items items) {
        Member member = TestOrganizations.join(users, items.organization().tenant(), false);
        Response profile = items.admin().postJson(PROFILES, "{\"name\":\"sharer-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[\"views.share\"]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        String profileId = JsonPath.read(profile.body(), "$.data.id");
        Response set = items.admin().request("PUT", MEMBERS + "/" + member.membership() + "/profile",
                "{\"profileId\":\"" + profileId + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        Response matrix = items.admin().request("PUT", PROFILES + "/" + profileId + "/data-access",
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":"
                        + ITEM_FIELD_PERMISSIONS + "}");
        assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        return member;
    }

    // ---- personal views ----

    @Test
    void aMemberSavesAPersonalViewAndGetsItBackExactlyAsSaved() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");

        Response created = save(person.browser(), "Mine", "PERSONAL");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.mine")).isTrue();
        assertThat(JsonPath.<String>read(created.body(), "$.data.scope")).isEqualTo("PERSONAL");
        assertThat(JsonPath.<Number>read(created.body(), "$.data.version").intValue()).isZero();
        Response list = person.browser().get(VIEWS);
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].label")).isEqualTo("Mine");
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].definition.filter.field")).isEqualTo("name__c");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[0].definition.filter.values")).containsExactly("x");
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data[0].definition.sort[0].descending")).isTrue();
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[0].definition.columns"))
                .containsExactly("name__c", "qty__c");
    }

    @Test
    void aPersonalViewIsSeenByItsMakerAloneAndAnyoneElseGetsNotFoundEvenAnAdministrator() {
        Items items = items();
        Person maker = person(items.organization(), items.admin(), "read");
        Person other = person(items.organization(), items.admin(), "read");
        String id = saved(maker.browser(), "Mine", "PERSONAL");

        assertThat(labels(maker.browser())).containsExactly("Mine");
        assertThat(labels(other.browser())).isEmpty();
        assertThat(labels(items.admin())).isEmpty();
        for (TestBrowser someone : List.of(other.browser(), items.admin())) {
            Response rename = someone.request("PUT", VIEWS + "/" + id, update("Renamed", "PERSONAL", 0));
            assertThat(rename.status()).isEqualTo(404);
            assertThat(someone.request("DELETE", VIEWS + "/" + id + "?version=0", null).status()).isEqualTo(404);
        }
        assertThat(labels(maker.browser())).as("untouched").containsExactly("Mine");
    }

    @Test
    void everyoneMayHaveAViewWithTheSameNameButNotTwoOfTheirOwn() {
        Items items = items();
        Person first = person(items.organization(), items.admin(), "read");
        Person second = person(items.organization(), items.admin(), "read");

        assertThat(save(first.browser(), "Mine", "PERSONAL").status()).isEqualTo(201);
        assertThat(save(second.browser(), "Mine", "PERSONAL").status()).isEqualTo(201);
        Response again = save(first.browser(), "mine", "PERSONAL");

        assertThat(again.status()).as("case does not make a name different").isEqualTo(409);
        assertThat(JsonPath.<String>read(again.body(), "$.error.message"))
                .isEqualTo("A list view with this name already exists.");
    }

    @Test
    void aNameCanBeUsedAgainOnceTheViewIsRemoved() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");
        String id = saved(person.browser(), "Mine", "PERSONAL");

        assertThat(person.browser().request("DELETE", VIEWS + "/" + id + "?version=0", null).status()).isEqualTo(204);

        assertThat(labels(person.browser())).isEmpty();
        assertThat(save(person.browser(), "Mine", "PERSONAL").status()).isEqualTo(201);
    }

    @Test
    void aChangeCarriesTheVersionThatWasReadAndAStaleOneChangesNothing() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");
        String id = saved(person.browser(), "Mine", "PERSONAL");

        Response renamed = person.browser().request("PUT", VIEWS + "/" + id, update("Better", "PERSONAL", 0));
        Response stale = person.browser().request("PUT", VIEWS + "/" + id, update("Stale", "PERSONAL", 0));
        Response staleDelete = person.browser().request("DELETE", VIEWS + "/" + id + "?version=0", null);

        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(renamed.body(), "$.data.version").intValue()).isEqualTo(1);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(staleDelete.status()).isEqualTo(409);
        assertThat(labels(person.browser())).containsExactly("Better");
        assertThat(person.browser().request("DELETE", VIEWS + "/" + id + "?version=1", null).status()).isEqualTo(204);
        assertThat(person.browser().request("DELETE", VIEWS + "/" + id + "?version=2", null).status())
                .as("a view that is gone").isEqualTo(404);
    }

    @Test
    void thereIsAMostViewsAPersonCanKeepForAnObject() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");
        for (int index = 0; index < 50; index++) {
            assertThat(save(person.browser(), "View " + index, "PERSONAL").status()).as("view " + index).isEqualTo(201);
        }

        Response over = save(person.browser(), "One too many", "PERSONAL");

        assertThat(over.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(over.body(), "$.error.message")).contains("most personal list views");
    }

    // ---- shared views ----

    @Test
    void aSharedViewNeedsTheAbilityAndIsThenSeenByEveryoneWhoMayReadTheObject() {
        Items items = items();
        Person plain = person(items.organization(), items.admin(), "read");
        Person reader = person(items.organization(), items.admin(), "read");
        Member sharer = sharer(items);

        Response refused = save(plain.browser(), "For everyone", "SHARED");
        String id = saved(items.admin(), "For everyone", "SHARED");
        Response asSharer = save(as(items.organization(), sharer), "From the sharer", "SHARED");

        assertThat(refused.status()).as("no ability, no shared view").isEqualTo(403);
        assertThat(asSharer.status()).as(asSharer.body()).isEqualTo(201);
        assertThat(labels(reader.browser())).containsExactly("For everyone", "From the sharer");
        Response seen = reader.browser().get(VIEWS);
        assertThat(JsonPath.<List<Boolean>>read(seen.body(), "$.data[*].mine")).containsExactly(false, false);
        assertThat(reader.browser().request("PUT", VIEWS + "/" + id, update("Hijacked", "SHARED", 0)).status())
                .as("a reader may not change a shared view").isEqualTo(403);
        assertThat(reader.browser().request("DELETE", VIEWS + "/" + id + "?version=0", null).status()).isEqualTo(403);
        assertThat(labels(reader.browser())).hasSize(2);
    }

    @Test
    void aPersonalViewCannotBecomeSharedWithoutTheAbilityAndASharedOneIsChangedByWhoeverMayShare() {
        Items items = items();
        Person plain = person(items.organization(), items.admin(), "read");
        Member sharer = sharer(items);
        TestBrowser sharerBrowser = as(items.organization(), sharer);
        String mine = saved(plain.browser(), "Mine", "PERSONAL");
        String theirs = saved(sharerBrowser, "Team", "SHARED");

        Response promote = plain.browser().request("PUT", VIEWS + "/" + mine, update("Mine", "SHARED", 0));
        Response byAdmin = items.admin().request("PUT", VIEWS + "/" + theirs, update("Team renamed", "SHARED", 0));
        Response removedByAdmin = items.admin().request("DELETE", VIEWS + "/" + theirs + "?version=1", null);

        assertThat(promote.status()).isEqualTo(403);
        assertThat(byAdmin.status()).as("an administrator may change what another member shared").isEqualTo(200);
        assertThat(removedByAdmin.status()).isEqualTo(204);
        assertThat(labels(plain.browser())).containsExactly("Mine");
    }

    @Test
    void onlyTheMakerOfASharedViewCanMakeItPersonalAgain() {
        Items items = items();
        Member first = sharer(items);
        Member second = sharer(items);
        TestBrowser maker = as(items.organization(), first);
        TestBrowser other = as(items.organization(), second);
        String id = saved(maker, "Team", "SHARED");

        Response refused = other.request("PUT", VIEWS + "/" + id, update("Team", "PERSONAL", 0));
        Response allowed = maker.request("PUT", VIEWS + "/" + id, update("Team", "PERSONAL", 0));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.scope"))
                .containsExactly("Only the person who made a view can make it personal.");
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(labels(other)).as("it is the maker's own again").isEmpty();
        assertThat(labels(maker)).containsExactly("Team");
    }

    @Test
    void aSharedNameIsUniqueAmongTheSharedViewsOfTheObjectButFreeAgainstAPersonalOne() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");
        saved(items.admin(), "Open", "SHARED");

        assertThat(save(items.admin(), "OPEN", "SHARED").status()).isEqualTo(409);
        assertThat(save(person.browser(), "Open", "PERSONAL").status()).as("a personal view of the same name")
                .isEqualTo(201);
    }

    // ---- what is checked when a view is saved ----

    @Test
    void aViewIsCheckedAgainstWhatTheSaverMayReadAndTheProblemsNameTheirPlacesNeverTheValues() {
        Items items = items();
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        Response unreadableFilter = member.postJson(VIEWS, body("A", "PERSONAL",
                "{\"filter\":{\"op\":\"EQ\",\"field\":\"qty__c\",\"values\":[\"secret-value\"]}}"));
        Response unreadableColumn = member.postJson(VIEWS,
                body("B", "PERSONAL", "{\"columns\":[\"name__c\",\"calc__c\"]}"));
        Response badComparison = member.postJson(VIEWS, body("C", "PERSONAL",
                "{\"filter\":{\"op\":\"NOPE\",\"field\":\"name__c\",\"values\":[\"x\"]}}"));
        Response twice = member.postJson(VIEWS, body("D", "PERSONAL", "{\"columns\":[\"name__c\",\"name__c\"]}"));

        assertThat(unreadableFilter.status()).as(unreadableFilter.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(unreadableFilter.body(), "$.error.fields['definition.filter.field']"))
                .containsExactly("This is not a field of the object.");
        assertThat(unreadableFilter.body()).as("a value is never echoed").doesNotContain("secret-value");
        assertThat(JsonPath.<List<String>>read(unreadableColumn.body(), "$.error.fields['definition.columns[1]']"))
                .containsExactly("This is not a field of the object.");
        assertThat(badComparison.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(badComparison.body(), "$.error.fields['definition.filter.op']"))
                .containsExactly("Choose a comparison from the list.");
        assertThat(JsonPath.<List<String>>read(twice.body(), "$.error.fields['definition.columns']"))
                .containsExactly("Each column can be listed once.");
        assertThat(labels(member)).as("nothing was saved").isEmpty();
    }

    @Test
    void theScopeAndTheNameAreChecked() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");

        Response scope = person.browser().postJson(VIEWS, body("A", "EVERYONE", "{}"));
        Response blank = person.browser().postJson(VIEWS, body("   ", "PERSONAL", "{}"));
        Response long81 = person.browser().postJson(VIEWS, body("x".repeat(81), "PERSONAL", "{}"));

        assertThat(scope.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(scope.body(), "$.error.fields.scope"))
                .containsExactly("Choose PERSONAL or SHARED.");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(long81.status()).isEqualTo(400);
    }

    // ---- a view grants nothing ----

    @Test
    void aSharedViewShowsNothingToSomeoneWhoCouldNotAskTheSameQuestion() {
        Items items = items();
        put(items.admin(), "{\"name__c\":\"a\",\"qty__c\":5}");
        saved(items.admin(), "Big ones", "SHARED");
        Holder limited = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), limited.member());

        // The view is visible to the member, but its definition is only a question: asking it is judged for them.
        String definition = "{\"filter\":{\"op\":\"GT\",\"field\":\"qty__c\",\"values\":[3]}}";
        Response asked = member.postJson(ITEM_QUERY, definition);

        assertThat(labels(member)).containsExactly("Big ones");
        assertThat(asked.status()).as("a field they may not read does not exist for them").isEqualTo(400);
        assertThat(asked.body()).doesNotContain("\"a\"");
    }

    // ---- who may ask ----

    @Test
    void aMemberWhoMayNotReadTheObjectIsAnsweredLikeForAnObjectThatDoesNotExist() {
        Items items = items();
        Holder nothing = holderWithMatrix(items.organization(), items.admin(), null);
        TestBrowser member = as(items.organization(), nothing.member());

        Response forbidden = member.get(VIEWS);
        Response unknown = member.get("/api/v1/objects/Nope__c/views");
        Response create = member.postJson(VIEWS, body("A", "PERSONAL", "{}"));

        assertThat(forbidden.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(create.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(forbidden.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
    }

    @Test
    void aViewOfAnotherOrganizationIsNotFoundAndNobodyGetsAnythingWithoutSigningIn() {
        Items items = items();
        Items other = items();
        String id = saved(other.admin(), "Theirs", "SHARED");
        TestBrowser nobody = new TestBrowser(port, items.organization().host());

        Response taken = items.admin().request("PUT", VIEWS + "/" + id, update("Mine now", "SHARED", 0));
        assertThat(taken.status()).isEqualTo(404);
        assertThat(items.admin().request("DELETE", VIEWS + "/" + id + "?version=0", null).status()).isEqualTo(404);
        assertThat(labels(items.admin())).isEmpty();
        assertThat(nobody.get(VIEWS).status()).isEqualTo(401);
        assertThat(nobody.postJson(VIEWS, body("A", "PERSONAL", "{}")).status()).isEqualTo(401);
        Response forged = items.admin().get(VIEWS, "X-Tenant-Id", other.organization().id().value().toString());
        assertThat(JsonPath.<List<String>>read(forged.body(), "$.data[*].label")).as("a forged header changes nothing")
                .isEmpty();
    }

    // ---- what is recorded ----

    @Test
    void theAuditRecordsSayWhichViewAndWhichScopeAndNeverTheNameOrTheDefinition() {
        Items items = items();
        Person person = person(items.organization(), items.admin(), "read");
        String id = saved(person.browser(), "A very private name", "PERSONAL");
        person.browser().request("PUT", VIEWS + "/" + id, update("Another private name", "PERSONAL", 0));
        person.browser().request("DELETE", VIEWS + "/" + id + "?version=1", null);

        List<String> records = owner("select event_type || ' ' || coalesce(attributes::text, '') from audit_record "
                + "where event_type like 'data.view.%' and context_tenant_id = ?::uuid order by occurred_at, id",
                items.organization().id().value().toString());

        assertThat(records).hasSize(3);
        assertThat(records.get(0)).startsWith("data.view.created").contains(id).contains("PERSONAL");
        assertThat(records.get(1)).startsWith("data.view.changed").contains("\"version\"");
        assertThat(records.get(2)).startsWith("data.view.removed").contains(id);
        assertThat(String.join(" ", records)).doesNotContain("private").doesNotContain("name__c")
                .doesNotContain("qty__c");
    }
}
