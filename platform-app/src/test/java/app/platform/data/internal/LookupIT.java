package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The names behind link fields and the records a link could point to (Sprint 19, ADR-0129): a person is named while
 * their membership is active, an ordinary record is named by the field that names it for this member and only when the
 * member may read it, and an answer never says why a name is missing.
 */
@PlatformIntegrationTest
class LookupIT extends RecordSecuritySupport {

    private static final String OWNER = ITEMS + "/lookups/ownerId";
    private static final String LINK = ITEMS + "/lookups/link__c";

    /** An organization with {@code Item__c}, and {@code Target__c} given a text field to be named by. */
    private Items named() {
        Items items = items();
        Response field = items.admin().postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"name\",\"label\":\"name\",\"type\":\"TEXT\"}");
        assertThat(field.status()).as(field.body()).isEqualTo(201);
        return items;
    }

    /** A person of the platform with this display name, who belongs to no organization. */
    private TestUser lone(String displayName) {
        String email = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
        String password = "Pw-" + UUID.randomUUID() + "-" + UUID.randomUUID();
        return new TestUser(users.createActive(email, displayName, password.toCharArray(), ActorId.SYSTEM), email,
                password);
    }

    /** A member of the organization with this display name. */
    private TestUser person(Organization organization, String displayName) {
        TestUser made = lone(displayName);
        TestOrganizations.join(organization.tenant(), made, false);
        return made;
    }

    private static String target(TestBrowser browser, String name) {
        Response created = browser.postJson(TARGETS, "{\"fields\":{\"name__c\":\"" + name + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }

    private static Response labels(TestBrowser browser, String path, String... ids) {
        return browser.postJson(path + "/labels", "{\"ids\":[" + String.join(",",
                java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").toList()) + "]}");
    }

    private static Map<String, String> named(Response answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.data.labels");
    }

    private static Response options(TestBrowser browser, String path, String text, int limit) {
        return browser.postJson(path + "/options", "{" + (text == null ? "" : "\"text\":\"" + text + "\"")
                + (limit > 0 ? (text == null ? "" : ",") + "\"limit\":" + limit : "") + "}");
    }

    private static List<String> offered(Response answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.data[*].label");
    }

    // ---- a person ----

    @Test
    void aPersonIsNamedByTheirDisplayNameWhileTheirMembershipIsActiveAndAnythingElseIsLeftOut() {
        Items items = named();
        TestUser alpha = person(items.organization(), "Member Alpha");
        TestUser outsider = lone("Member Outside");
        String missing = UUID.randomUUID().toString();

        Map<String, String> names = named(labels(items.admin(), OWNER, alpha.user().id().toString(),
                outsider.user().id().toString(), missing));

        assertThat(names).containsOnlyKeys(alpha.user().id().toString()).containsValue("Member Alpha");
    }

    @Test
    void aPersonWhoLeftIsNoLongerNamedAndAnIdentifierOfAnotherOrganizationIsNeverNamed() {
        Items items = named();
        Items other = named();
        TestUser alpha = person(items.organization(), "Member Alpha");
        TestUser elsewhere = person(other.organization(), "Member Elsewhere");

        assertThat(named(labels(items.admin(), OWNER, alpha.user().id().toString()))).hasSize(1);
        ownerRuns("update membership set status = 'DEACTIVATED', version = version + 1 where user_id = '"
                + alpha.user().id() + "'::uuid "
                + "and tenant_id = '" + items.organization().id().value() + "'::uuid");

        assertThat(named(labels(items.admin(), OWNER, alpha.user().id().toString()))).isEmpty();
        assertThat(named(labels(items.admin(), OWNER, elsewhere.user().id().toString())))
                .as("the same answer as for a person who does not exist").isEmpty();
    }

    @Test
    void aPersonIsFoundByPartOfTheirNameCaseDoesNotMatterAndThePatternCharactersAreOnlyCharacters() {
        Items items = named();
        person(items.organization(), "Member Alpha");
        person(items.organization(), "Member Beta");
        person(items.organization(), "Odd 100% name_here");

        assertThat(offered(options(items.admin(), OWNER, "alph", 0))).containsExactly("Member Alpha");
        assertThat(offered(options(items.admin(), OWNER, "MEMBER", 1))).as("a few at a time")
                .hasSize(1);
        assertThat(offered(options(items.admin(), OWNER, "%", 0))).containsExactly("Odd 100% name_here");
        assertThat(offered(options(items.admin(), OWNER, "_", 0))).containsExactly("Odd 100% name_here");
        assertThat(offered(options(items.admin(), OWNER, "nothing like it", 0))).isEmpty();
    }

    @Test
    void theNumberOfPeopleOfferedIsBoundedWhateverIsAsked() {
        Items items = named();
        for (int index = 0; index < 30; index++) {
            person(items.organization(), "Member Number " + index);
        }

        assertThat(offered(options(items.admin(), OWNER, "Member", 100))).hasSize(25);
        assertThat(offered(options(items.admin(), OWNER, "Member", 0))).as("ten by default").hasSize(10);
    }

    // ---- an ordinary record ----

    @Test
    void anOrdinaryRecordIsNamedByTheFieldThatNamesItAndOnlyRecordsOfThisOrganization() {
        Items items = named();
        Items other = named();
        String acme = target(items.admin(), "Acme");
        String globex = target(items.admin(), "Globex");
        String foreign = target(other.admin(), "Foreign");

        Map<String, String> names = named(labels(items.admin(), LINK, acme, globex, foreign));

        assertThat(names).containsOnly(Map.entry(acme, "Acme"), Map.entry(globex, "Globex"));
    }

    @Test
    void aPickerOffersMatchingRecordsByNameAndWithoutTextTheFirstOnes() {
        Items items = named();
        target(items.admin(), "Acme");
        target(items.admin(), "Acorn");
        target(items.admin(), "Globex");

        assertThat(offered(options(items.admin(), LINK, "Ac", 0))).containsExactlyInAnyOrder("Acme", "Acorn");
        assertThat(offered(options(items.admin(), LINK, null, 0))).containsExactly("Acme", "Acorn", "Globex");
        assertThat(offered(options(items.admin(), LINK, "zzz", 0))).isEmpty();
    }

    @Test
    void aMemberIsNamedOnlyTheRecordsTheyMayReadAndAMissingNameNeverSaysWhy() {
        Items items = named();
        TestBrowser admin = items.admin();
        String shared = target(admin, "Shared");
        // The administrator owns both; the object is private, so the member reaches neither until one is shared.
        Response setting = admin.request("PUT", SHARING + "/objects/Target__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false}");
        assertThat(setting.status()).as(setting.body()).isEqualTo(200);
        String hidden = target(admin, "Hidden");
        Holder holder = holderWithMatrix(items.organization(), admin, "{\"objects\":["
                + "{\"key\":\"Item__c\",\"actions\":[\"read\"]},{\"key\":\"Target__c\",\"actions\":[\"read\"]}],"
                + "\"fields\":[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]},"
                + "{\"key\":\"Item__c.link__c\",\"actions\":[\"read\"]},"
                + "{\"key\":\"Target__c.name__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());
        Response share = admin.request("PUT", TARGETS + "/" + shared + "/shares", "{\"granteeType\":\"USER\","
                + "\"granteeId\":\"" + holder.member().person().user().id() + "\",\"accessLevel\":\"READ\"}");
        assertThat(share.status()).as(share.body()).isEqualTo(200);

        Map<String, String> names = named(labels(member, LINK, shared, hidden, UUID.randomUUID().toString()));

        // The member may not read the identifier as a field, so the names are read one record at a time: the same gate.
        assertThat(names).as("only the record they reach is named").containsOnly(Map.entry(shared, "Shared"));
        assertThat(offered(options(member, LINK, null, 0))).as("a picker offers what they reach")
                .containsExactly("Shared");
    }

    @Test
    void aMemberWhoMayNotReadTheTargetObjectIsNamedNothingAndOffersNothing() {
        Items items = named();
        String acme = target(items.admin(), "Acme");
        Holder holder = holderWithMatrix(items.organization(), items.admin(), "{\"objects\":["
                + "{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":["
                + "{\"key\":\"Item__c.link__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        assertThat(named(labels(member, LINK, acme))).isEmpty();
        assertThat(offered(options(member, LINK, null, 0))).isEmpty();
    }

    // ---- what may be asked ----

    @Test
    void aFieldThatIsNotALinkOrThatTheMemberMayNotReadOrThatDoesNotExistAnswersAlike() {
        Items items = named();
        Holder holder = holderWithMatrix(items.organization(), items.admin(), "{\"objects\":["
                + "{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":["
                + "{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        Response notALink = labels(items.admin(), ITEMS + "/lookups/name__c", UUID.randomUUID().toString());
        Response unreadable = labels(member, LINK, UUID.randomUUID().toString());
        Response unknown = labels(items.admin(), ITEMS + "/lookups/nothing__c", UUID.randomUUID().toString());

        for (Response refused : List.of(notALink, unreadable, unknown)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(404);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                    .isEqualTo("This is not a link to another object.");
        }
        assertThat(options(items.admin(), ITEMS + "/lookups/name__c", null, 0).status()).isEqualTo(404);
    }

    @Test
    void anObjectTheMemberMayNotReadAnswersLikeOneThatDoesNotExist() {
        Items items = named();
        Holder nothing = holderWithMatrix(items.organization(), items.admin(), null);
        TestBrowser member = as(items.organization(), nothing.member());

        Response forbidden = labels(member, LINK, UUID.randomUUID().toString());
        Response unknown = labels(member, "/api/v1/objects/Nope__c/lookups/link__c", UUID.randomUUID().toString());

        assertThat(forbidden.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(forbidden.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
    }

    @Test
    void whatIsNotAnIdentifierOrIsTooManyIsRefusedAtItsPlace() {
        Items items = named();
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add("\"" + UUID.randomUUID() + "\"");
        }

        Response bad = items.admin().postJson(LINK + "/labels",
                "{\"ids\":[\"" + UUID.randomUUID() + "\",\"x'; drop\"]}");
        Response many = items.admin().postJson(LINK + "/labels", "{\"ids\":[" + String.join(",", tooMany) + "]}");
        Response none = items.admin().postJson(LINK + "/labels", "{\"ids\":[]}");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(bad.body(), "$.error.fields['ids[1]']"))
                .containsExactly("Is not an identifier.");
        assertThat(bad.body()).as("what was typed is not repeated").doesNotContain("drop");
        assertThat(many.status()).isEqualTo(400);
        assertThat(none.status()).isEqualTo(400);
    }

    @Test
    void nobodyIsNamedAnythingWithoutSigningIn() {
        Items items = named();
        TestBrowser nobody = new TestBrowser(port, items.organization().host());

        assertThat(labels(nobody, OWNER, UUID.randomUUID().toString()).status()).isEqualTo(401);
        assertThat(options(nobody, OWNER, null, 0).status()).isEqualTo(401);
    }
}
