package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the Sprint 12 integration tests share (page layouts, record pages, applications, layout assignments): an
 * organization with a {@code Deal} object (a required text field, a picklist, a plain text field) and a {@code Contact}
 * object that points at it, and short ways to make the definitions through the real API.
 */
abstract class DefinitionsSupport extends LifecycleSupport {

    protected static final String APPLICATIONS = "/api/v1/metadata/applications";
    protected static final String MY_APPLICATIONS = "/api/v1/applications";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String MEMBERS = "/api/v1/members";

    /** A layout that shows the required field of {@code Deal}. */
    protected static final String DEAL_LAYOUT = "{\"sections\":[{\"label\":\"Main\",\"columns\":2,\"fields\":["
            + "{\"field\":\"code__c\",\"behaviour\":\"REQUIRED\"},{\"field\":\"stage__c\"}]}]}";

    /** A second layout, with another arrangement of the same fields. */
    protected static final String DEAL_LAYOUT_TWO = "{\"sections\":[{\"label\":\"Everything\",\"fields\":["
            + "{\"field\":\"code__c\"},{\"field\":\"stage__c\",\"behaviour\":\"READ_ONLY\"},"
            + "{\"field\":\"department__c\",\"behaviour\":\"HIDDEN\"}]}]}";

    /** An organization with its administrator, {@code Deal__c} and {@code Contact__c} (which looks up a deal). */
    protected TestBrowser adminWithDealAndContact(Organization organization) {
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Deal");
        createField(admin, "Deal__c", requiredText("code"));
        createField(admin, "Deal__c", picklist("stage", "Prospect", "Won", "Lost"));
        createField(admin, "Deal__c", text("department"));
        createObject(admin, "Contact");
        createField(admin, "Contact__c", text("surname"));
        Response lookup = createField(admin, "Contact__c", reference("deal", "LOOKUP", "Deal__c", ""));
        assertThat(lookup.status()).as(lookup.body()).isEqualTo(201);
        return admin;
    }

    protected TestBrowser adminWithDealAndContact() {
        return adminWithDealAndContact(organization());
    }

    // ---- layouts ----

    protected static String layoutRequest(String name, String definition) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"definition\":"
                + definition + "}";
    }

    protected static String layoutUpdate(String name, String definition, long version) {
        return "{\"label\":\"" + name + "\",\"description\":\"\",\"definition\":" + definition + ",\"version\":"
                + version + "}";
    }

    protected static Response createLayout(TestBrowser admin, String object, String name, String definition) {
        return admin.postJson(OBJECTS + "/" + object + "/layouts", layoutRequest(name, definition));
    }

    protected static void layoutExists(TestBrowser admin, String object, String name, String definition) {
        Response created = createLayout(admin, object, name, definition);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    // ---- record pages ----

    /** A record page that shows the details of the layout and lists the contacts of the deal. */
    protected static String pageDefinition(String layout) {
        return "{\"layout\":\"" + layout + "\",\"components\":["
                + "{\"key\":\"head\",\"type\":\"HEADER\",\"titleField\":\"code__c\","
                + "\"highlightFields\":[\"stage__c\"]},"
                + "{\"key\":\"details\",\"type\":\"DETAILS\"},"
                + "{\"key\":\"contacts\",\"type\":\"RELATED_LIST\",\"object\":\"Contact__c\",\"field\":\"deal__c\","
                + "\"columns\":[\"surname__c\"],\"limit\":10,\"region\":\"SIDEBAR\","
                + "\"visibleWhen\":{\"all\":[{\"field\":\"stage__c\",\"operator\":\"NOT_EQUALS\",\"value\":\"Lost\"},"
                + "{\"any\":[{\"ability\":\"members.view\"},{\"field\":\"code__c\",\"operator\":\"IS_NOT_EMPTY\"}]}]}},"
                + "{\"key\":\"more\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"History\",\"components\":["
                + "{\"key\":\"log\",\"type\":\"HISTORY\"}]}]}],"
                + "\"actions\":[{\"action\":\"EDIT\"},{\"action\":\"DELETE\",\"label\":\"Remove\","
                + "\"visibleWhen\":{\"ability\":\"members.deactivate\"}}]}";
    }

    protected static Response createRecordPage(TestBrowser admin, String object, String name, String definition) {
        return admin.postJson(OBJECTS + "/" + object + "/record-pages", layoutRequest(name, definition));
    }

    // ---- applications ----

    protected static Response createApplication(TestBrowser admin, String name, String definition) {
        return admin.postJson(APPLICATIONS, layoutRequest(name, definition));
    }

    protected static String applicationDefinition(String profilesJson, String itemsJson) {
        return "{\"iconKey\":\"briefcase\",\"profiles\":" + profilesJson + ",\"items\":" + itemsJson + "}";
    }

    protected static final String DEAL_ITEMS = "[{\"type\":\"HEADING\",\"label\":\"Work\"},"
            + "{\"type\":\"OBJECT\",\"object\":\"Deal__c\"},"
            + "{\"type\":\"LINK\",\"label\":\"Members\",\"path\":\"/members\"}]";

    // ---- profiles and members ----

    /** A new profile with the abilities, and a new member who holds it; returns both. */
    protected record Holder(Member member, String profileId) {
    }

    protected Holder holderOf(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        String profile = newProfile(admin, abilities);
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + profile + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return new Holder(person, profile);
    }

    /** Lets the holders of the profile read the objects (and nothing else), so the menu lists them. */
    protected static void allowReading(TestBrowser admin, String profileId, String... objects) {
        String listed = String.join(",", java.util.Arrays.stream(objects)
                .map(object -> "{\"key\":\"" + object + "\",\"actions\":[\"read\"]}").toList());
        Response set = admin.request("PUT", PROFILES + "/" + profileId + "/data-access",
                "{\"objects\":[" + listed + "],\"fields\":[]}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
    }

    protected static String newProfile(TestBrowser admin, String... abilities) {
        String quoted = String.join(",", java.util.Arrays.stream(abilities).map(a -> "\"" + a + "\"").toList());
        Response profile = admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + quoted + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        return JsonPath.read(profile.body(), "$.data.id");
    }

    // ---- reading back ----

    /** The definition of a layout, record page or application as the API answers it. */
    protected static Object definitionOf(Response response) {
        return JsonPath.read(response.body(), "$.data.definition");
    }

    protected static long versionOf(Response response) {
        return ((Number) JsonPath.read(response.body(), "$.data.version")).longValue();
    }

    protected static String json(Response response, String path) {
        return JsonPath.read(response.body(), path);
    }

    /** The places (fields of the request, or paths inside a definition) that a VALIDATION_ERROR names. */
    protected static List<String> places(Response refused) {
        Map<String, Object> fields = JsonPath.read(refused.body(), "$.error.fields");
        return new ArrayList<>(fields.keySet());
    }
}
