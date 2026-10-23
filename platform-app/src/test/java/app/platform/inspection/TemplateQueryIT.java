package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The question a list view puts to the master list and to the fill-in list (UI3A, ADR-0194): a search text, the
 * conditions and the order of a view, asked of the server for the whole result. The answer is what the plain list gives
 * for the same question (a differential check), a person never reaches a record through a filter, a search or an order
 * that they could not reach otherwise, every refusal is the one of the rest of the API, and nothing typed is logged.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + TemplateQueryIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class TemplateQueryIT extends FillSupport {

    static final String LOG_FILE = "target/it-logs/template-query-clean.log";
    private static final String QUERY = TEMPLATES + "/query";
    private static final String FILL_QUERY = FILL_INS + "/query";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static List<String> ids(Response page) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].id");
    }

    private static List<String> asked(TestBrowser browser, String body) {
        return ids(browser.postJson(QUERY, body));
    }

    private static String named(Person person, String name, String category, String subCategory) {
        String template = template(person.browser(), "{\"name\":\"" + name + "\",\"category\":\"" + category + "\""
                + (subCategory == null ? "" : ",\"subCategory\":\"" + subCategory + "\"") + "}");
        fill(person.browser(), template, firstVersion(person.browser(), template));
        return template;
    }

    private static String condition(String op, String field, String value) {
        return "{\"op\":\"" + op + "\",\"field\":\"" + field + "\",\"values\":[\"" + value + "\"]}";
    }

    // ---- the same answer as the plain list ----

    @Test
    void theQueryGivesExactlyWhatThePlainListGivesForTheSameQuestion() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        String fire = named(author, "Fire exit check", "WI", "PRD");
        String stores = named(author, "Stores audit", "SOP", "QLT");
        String round = named(author, "Quality round", "WI", "QLT");
        TestBrowser browser = approver.browser();

        assertThat(asked(browser, "{}")).containsExactlyInAnyOrderElementsOf(ids(browser.get(TEMPLATES)));
        assertThat(asked(browser, "{\"search\":\"exit\"}")).containsExactly(fire);
        assertThat(asked(browser, "{\"filter\":" + condition("EQ", "category", "WI") + "}"))
                .containsExactlyInAnyOrder(fire, round);
        assertThat(asked(browser, "{\"filter\":{\"op\":\"AND\",\"items\":[" + condition("EQ", "category", "WI") + ","
                + condition("EQ", "subCategory", "QLT") + "]}}")).containsExactly(round);
        assertThat(asked(browser, "{\"filter\":" + condition("CONTAINS", "name", "audit") + "}"))
                .containsExactly(stores);
        assertThat(asked(browser, "{\"search\":\"SOP\"}")).as("a whole category code is found").containsExactly(stores);
        List<String> ascending = asked(browser, "{\"sort\":[{\"field\":\"name\",\"descending\":false}]}");
        List<String> descending = asked(browser, "{\"sort\":[{\"field\":\"name\",\"descending\":true}]}");
        assertThat(ascending).containsExactly(fire, round, stores);
        assertThat(descending).containsExactly(stores, round, fire);
        assertThat(asked(browser, "{}")).as("no order given is the name order").isEqualTo(ascending);
    }

    @Test
    void aSearchAFilterAndAnOrderWorkTogetherAndTheCursorPagesWithoutRepeatingOrSkipping() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        List<String> expected = new ArrayList<>();
        for (String name : List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo")) {
            expected.add(named(author, name + " check", "WI", "PRD"));
        }
        named(author, "Other thing", "SOP", "PRD");
        TestBrowser browser = approver.browser();
        String body = "{\"search\":\"check\",\"filter\":" + condition("EQ", "category", "WI")
                + ",\"sort\":[{\"field\":\"name\",\"descending\":true}],\"size\":2";

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            Response answer = browser.postJson(QUERY, body + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"")
                    + "}");
            seen.addAll(ids(answer));
            cursor = JsonPath.read(answer.body(), "$.data.nextCursor");
            if (cursor == null) {
                break;
            }
        }

        List<String> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);
        assertThat(seen).as("every match once, in the order asked").containsExactlyElementsOf(reversed);
    }

    // ---- who may reach what ----

    @Test
    void aPersonNeverReachesARecordThroughASearchAFilterOrAnOrderTheyCouldNotReachOtherwise() {
        Setting setting = setting();
        Person authorA = author(setting);
        Person authorB = author(setting);
        Person approver = approver(setting);
        String mine = named(authorA, "Shared words here", "WI", "PRD");
        String theirs = named(authorB, "Shared words there", "WI", "PRD");

        assertThat(asked(authorA.browser(), "{\"search\":\"Shared words\"}")).containsExactly(mine);
        assertThat(asked(authorB.browser(), "{\"search\":\"Shared words\"}")).containsExactly(theirs);
        assertThat(asked(authorA.browser(), "{\"filter\":" + condition("EQ", "category", "WI")
                + ",\"sort\":[{\"field\":\"name\"}]}")).containsExactly(mine);
        assertThat(asked(authorB.browser(), "{\"search\":\"words here\"}")).as("another person's name finds nothing")
                .isEmpty();
        assertThat(asked(approver.browser(), "{\"search\":\"Shared words\"}"))
                .as("an approver may view every template").containsExactlyInAnyOrder(mine, theirs);
    }

    @Test
    void aFieldTheCallerCannotNameIsRefusedLikeOneThatDoesNotExistAndTheValueIsNeverEchoed() {
        Setting setting = setting();
        Person author = author(setting);
        named(author, "Anything", "WI", "PRD");
        String needle = "needle-" + UUID.randomUUID().toString().replace("-", "");

        Response filter = author.browser().postJson(QUERY, "{\"filter\":" + condition("EQ", "noSuchField", needle)
                + "}");
        Response order = author.browser().postJson(QUERY, "{\"sort\":[{\"field\":\"noSuchField\"}]}");
        Response operator = author.browser().postJson(QUERY, "{\"filter\":" + condition("TELEPORT", "name", needle)
                + "}");

        for (Response refused : List.of(filter, order, operator)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).doesNotContain(needle);
        }
    }

    @Test
    void aBadBodyIsRefusedBeforeAnythingIsAsked() {
        Setting setting = setting();
        Person approver = approver(setting);
        TestBrowser browser = approver.browser();

        assertThat(browser.postJson(QUERY, "{\"state\":\"SOMETHING\"}").status()).isEqualTo(400);
        assertThat(browser.postJson(QUERY,
                "{\"sort\":[{\"field\":\"name\"},{\"field\":\"number\"},{\"field\":\"category\"}]}").status())
                .isEqualTo(400);
        assertThat(browser.postJson(QUERY, "{\"search\":\"" + "x".repeat(81) + "\"}").status()).isEqualTo(400);
        assertThat(browser.postJson(QUERY, "not json").status()).isEqualTo(400);
        assertThat(browser.postJsonWithoutCsrfHeader(QUERY, "{}").status()).as("no forgery protection")
                .isEqualTo(403);
    }

    @Test
    void anotherOrganizationAForgedHostThePlatformAndSomeoneNotSignedInGetNothingOfIt() {
        Setting mine = setting();
        Setting theirs = setting();
        Person authorMine = author(mine);
        Person approverTheirs = approver(theirs);
        String template = named(authorMine, "Only mine", "WI", "PRD");
        String[] forged = {"X-Tenant-Id", theirs.organization().id().value().toString(), "X-Forwarded-Host",
            theirs.organization().host(), "X-Organization", theirs.organization().tenant().slug()};

        assertThat(asked(approverTheirs.browser(), "{\"search\":\"Only mine\"}")).as("another organization").isEmpty();
        assertThat(asked(authorMine.browser(), "{\"search\":\"Only mine\"}")).containsExactly(template);
        Response forgedAnswer = authorMine.browser().postJson(QUERY, "{\"search\":\"Only mine\"}", forged);
        assertThat(ids(forgedAnswer)).as("a forged header changes nothing").containsExactly(template);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        assertThat(platform.postJson(QUERY, "{}").status()).isEqualTo(404);
        TestBrowser nobody = new TestBrowser(port, mine.organization().host());
        Response unauthenticated = nobody.postJson(QUERY, "{}");
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(unauthenticated.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
    }

    // ---- the fill-in list ----

    @Test
    void theFillInQueryGivesWhatThePlainListGivesAndNeverShowsAnotherPersonsFillIn() {
        World world = world();
        String mine = started(world.filler().browser(), world.template());
        String theirs = started(world.colleague().browser(), world.template());

        List<String> plain = listedFills(world.filler().browser(), "");
        Response query = world.filler().browser().postJson(FILL_QUERY, "{}");
        assertThat(query.status()).as(query.body()).isEqualTo(200);
        List<String> queried = JsonPath.read(query.body(), "$.data.rows[*].fillId");

        assertThat(queried).containsExactlyElementsOf(plain).containsExactly(mine);
        Response narrowed = world.filler().browser().postJson(FILL_QUERY, "{\"templateId\":\"" + world.template()
                + "\",\"status\":\"DRAFT\",\"sort\":[{\"field\":\"createdAt\",\"descending\":true}]}");
        assertThat(JsonPath.<List<String>>read(narrowed.body(), "$.data.rows[*].fillId")).containsExactly(mine);
        Response viewAll = world.reviewer().browser().postJson(FILL_QUERY,
                "{\"filter\":" + condition("EQ", "status", "DRAFT") + "}");
        assertThat(JsonPath.<List<String>>read(viewAll.body(), "$.data.rows[*].fillId"))
                .containsExactlyInAnyOrder(mine, theirs);
        assertThat(world.filler().browser().postJson(FILL_QUERY, "{\"status\":\"NOPE\"}").status()).isEqualTo(400);
        assertThat(new TestBrowser(port, world.setting().organization().host()).postJson(FILL_QUERY, "{}").status())
                .isEqualTo(401);
    }

    // ---- nothing typed is logged ----

    @Test
    void nothingTypedInASearchAFilterOrAViewIsLoggedEchoedOrAudited() throws IOException {
        Setting setting = setting();
        Person author = author(setting);
        named(author, "Logged name", "WI", "PRD");
        String search = "search-" + UUID.randomUUID().toString().replace("-", "");
        String value = "value-" + UUID.randomUUID().toString().replace("-", "");
        String viewName = "view-" + UUID.randomUUID().toString().replace("-", "");

        Response searched = author.browser().postJson(QUERY, "{\"search\":\"" + search + "\"}");
        Response filtered = author.browser().postJson(QUERY, "{\"filter\":" + condition("CONTAINS", "name", value)
                + "}");
        Response refused = author.browser().postJson(QUERY, "{\"filter\":" + condition("EQ", "noSuchField", value)
                + "}");
        Response fills = author.browser().postJson(FILL_QUERY, "{\"search\":\"" + search + "\"}");
        Response view = author.browser().postJson("/api/v1/objects/InspectionTemplate/views",
                "{\"label\":\"" + viewName + "\",\"scope\":\"PERSONAL\",\"definition\":{\"filter\":"
                + condition("CONTAINS", "name", value) + ",\"sort\":[],\"columns\":[\"number\"]}}");

        assertThat(searched.status()).isEqualTo(200);
        assertThat(filtered.status()).isEqualTo(200);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(fills.status()).isIn(200, 404);
        assertThat(view.status()).as(view.body()).isEqualTo(201);
        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(search, value, viewName)) {
            assertThat(log).as("not in the log: " + text).doesNotContain(text);
            assertThat(refused.body()).doesNotContain(text);
        }
        assertThat(owner("select coalesce(string_agg(attributes::text, ' '), '') from audit_record where "
                + "context_tenant_id = ?", setting.organization().id().value()).get(0))
                .as("nor in the audit trail").doesNotContain(search).doesNotContain(value).doesNotContain(viewName);
    }
}
