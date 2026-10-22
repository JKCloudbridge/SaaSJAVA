package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The master list of templates (FA4, ADR-0174 section 1, ADR-0188): one page of headers read through the record store,
 * so that two people see different lists from the same data, with the lifecycle of each template added from one
 * statement that never reads a grid; the filters, the search, the order and the cursor.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateListIT extends TemplateSupport {

    private record Run(Setting setting, Person authorA, Person authorB, Person approver, Person reader, String process,
            String departmentGroup) {
    }

    private Run run() {
        Setting setting = setting();
        Person authorA = author(setting);
        Person authorB = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String group = group(setting.admin(), "dept-" + System.nanoTime(), reader);
        department(setting.admin(), "PLANT1", "PRD", group);
        return new Run(setting, authorA, authorB, approver, reader, process, group);
    }

    private static List<String> rows(Response page, String field) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*]." + field);
    }

    private static String named(Person person, String name, String category, String subCategory) {
        String template = template(person.browser(), "{\"name\":\"" + name + "\",\"category\":\"" + category + "\""
                + (subCategory == null ? "" : ",\"subCategory\":\"" + subCategory + "\"") + "}");
        fill(person.browser(), template, firstVersion(person.browser(), template));
        return template;
    }

    // ---- two people, one set of data ----

    @Test
    void twoPeopleSeeDifferentListsFromTheSameData() {
        Run run = run();
        String a1 = named(run.authorA(), "Alpha", "WI", "PRD");
        String a2 = named(run.authorA(), "Bravo", "SOP", "QLT");
        String b1 = named(run.authorB(), "Charlie", "WI", "QLT");

        List<String> forA = rows(run.authorA().browser().get(TEMPLATES), "id");
        List<String> forB = rows(run.authorB().browser().get(TEMPLATES), "id");
        List<String> forApprover = rows(run.approver().browser().get(TEMPLATES), "id");
        List<String> forReader = rows(run.reader().browser().get(TEMPLATES), "id");
        List<String> forAdmin = rows(run.setting().admin().get(TEMPLATES), "id");

        assertThat(forA).containsExactlyInAnyOrder(a1, a2);
        assertThat(forB).containsExactly(b1);
        assertThat(forApprover).as("an approver may view every template").containsExactlyInAnyOrder(a1, a2, b1);
        assertThat(forAdmin).containsExactlyInAnyOrder(a1, a2, b1);
        assertThat(forReader).as("nothing is published to them yet").isEmpty();

        String request = submitted(run.authorA().browser(), a1, run.process());
        assertThat(approve(run.approver().browser(), request).status()).isEqualTo(200);
        assertThat(publish(run.approver().browser(), a1, null, "PLANT1/PRD").status()).isEqualTo(200);

        assertThat(rows(run.reader().browser().get(TEMPLATES), "id")).containsExactly(a1);
        assertThat(rows(run.authorB().browser().get(TEMPLATES), "id")).as("B still sees only their own")
                .containsExactly(b1);
    }

    @Test
    void eachRowCarriesTheLifecycleOfItsTemplateAndTheStateAsOneWord() {
        Run run = run();
        String draft = named(run.authorA(), "Draft one", "WI", "PRD");
        String active = named(run.authorA(), "Active one", "WI", "PRD");
        assertThat(approve(run.approver().browser(), submitted(run.authorA().browser(), active, run.process()))
                .status()).isEqualTo(200);
        assertThat(publish(run.approver().browser(), active, null, "PLANT1/PRD").status()).isEqualTo(200);
        int two = newVersion(run.authorA().browser(), active);
        fill(run.authorA().browser(), active, two);
        submitted(run.authorA().browser(), active, run.process());
        String retired = named(run.authorA(), "Retired one", "SOP", "PRD");
        approve(run.approver().browser(), submitted(run.authorA().browser(), retired, run.process()));
        assertThat(retire(run.approver().browser(), retired).status()).isEqualTo(200);

        Response page = run.approver().browser().get(TEMPLATES + "?size=200");

        List<String> ids = rows(page, "id");
        assertThat(JsonPath.<String>read(page.body(), "$.data.rows[" + ids.indexOf(draft) + "].lifecycle.state"))
                .isEqualTo("DRAFT");
        int at = ids.indexOf(active);
        assertThat(JsonPath.<String>read(page.body(), "$.data.rows[" + at + "].lifecycle.state")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(page.body(), "$.data.rows[" + at + "].lifecycle.activeVersion")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(page.body(), "$.data.rows[" + at + "].lifecycle.inReviewVersion"))
                .isEqualTo(2);
        assertThat(JsonPath.<String>read(page.body(), "$.data.rows[" + ids.indexOf(retired) + "].lifecycle.state"))
                .isEqualTo("RETIRED");
        assertThat(JsonPath.<Boolean>read(page.body(), "$.data.rows[" + ids.indexOf(retired) + "].retired")).isTrue();
        assertThat(JsonPath.<String>read(page.body(), "$.data.rows[" + at + "].number")).isEqualTo("WI/PRD/01");
    }

    // ---- filters, search and order ----

    @Test
    void theFiltersNarrowTheListAndTheStateFilterSaysItIsAppliedAfterThePage() {
        Run run = run();
        String wi = named(run.authorA(), "Fire exit check", "WI", "PRD");
        String sop = named(run.authorA(), "Stores audit", "SOP", "QLT");
        String wiQlt = named(run.authorA(), "Quality round", "WI", "QLT");
        approve(run.approver().browser(), submitted(run.authorA().browser(), wi, run.process()));
        approve(run.approver().browser(), submitted(run.authorA().browser(), sop, run.process()));
        assertThat(retire(run.approver().browser(), sop).status()).isEqualTo(200);
        var browser = run.approver().browser();

        assertThat(rows(browser.get(TEMPLATES + "?category=WI"), "id")).containsExactlyInAnyOrder(wi, wiQlt);
        assertThat(rows(browser.get(TEMPLATES + "?category=WI&subCategory=QLT"), "id")).containsExactly(wiQlt);
        assertThat(rows(browser.get(TEMPLATES + "?search=exit"), "id")).containsExactly(wi);
        assertThat(rows(browser.get(TEMPLATES + "?search=WI/PRD"), "id")).as("by number").containsExactly(wi);
        assertThat(rows(browser.get(TEMPLATES + "?retired=true"), "id")).containsExactly(sop);
        assertThat(rows(browser.get(TEMPLATES + "?retired=false"), "id")).containsExactlyInAnyOrder(wi, wiQlt);
        Response approved = browser.get(TEMPLATES + "?state=APPROVED");
        assertThat(rows(approved, "id")).containsExactly(wi);
        assertThat(JsonPath.<Boolean>read(approved.body(), "$.data.filtered")).isTrue();
        assertThat(rows(browser.get(TEMPLATES + "?state=DRAFT"), "id")).containsExactly(wiQlt);
        assertThat(rows(browser.get(TEMPLATES + "?state=RETIRED"), "id")).containsExactly(sop);
        assertThat(JsonPath.<Boolean>read(browser.get(TEMPLATES).body(), "$.data.filtered")).isFalse();
    }

    @Test
    void theListIsOrderedByNameByNumberOrNewestFirst() {
        Run run = run();
        String zulu = named(run.authorA(), "Zulu", "WI", "PRD");
        String alpha = named(run.authorA(), "Alpha", "WI", "PRD");
        String mike = named(run.authorA(), "Mike", "WI", "PRD");
        approve(run.approver().browser(), submitted(run.authorA().browser(), mike, run.process()));
        approve(run.approver().browser(), submitted(run.authorA().browser(), zulu, run.process()));
        var browser = run.approver().browser();

        assertThat(rows(browser.get(TEMPLATES + "?sort=name"), "id")).containsExactly(alpha, mike, zulu);
        assertThat(rows(browser.get(TEMPLATES), "id")).as("name is the default").containsExactly(alpha, mike, zulu);
        assertThat(rows(browser.get(TEMPLATES + "?sort=newest"), "id")).containsExactly(mike, alpha, zulu);
        List<String> byNumber = rows(browser.get(TEMPLATES + "?sort=number&retired=false&category=WI"), "number");
        assertThat(byNumber).containsExactly("WI/PRD/01", "WI/PRD/02");
    }

    @Test
    void theCursorPagesThroughTheListWithoutRepeatingOrSkipping() {
        Run run = run();
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            made.add(named(run.authorA(), "Template " + i, "WI", "PRD"));
        }
        var browser = run.authorA().browser();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = browser.get(TEMPLATES + "?size=2&sort=name" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            seen.addAll(rows(page, "id"));
            cursor = optional(page.body(), "$.data.nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(made).doesNotHaveDuplicates();
    }

    @Test
    void theMemberWhoMayCreateIsToldSoAndTheReaderIsNot() {
        Run run = run();

        assertThat(JsonPath.<Boolean>read(run.authorA().browser().get(TEMPLATES).body(), "$.data.canCreate")).isTrue();
        assertThat(JsonPath.<Boolean>read(run.reader().browser().get(TEMPLATES).body(), "$.data.canCreate"))
                .isFalse();
        assertThat(JsonPath.<Boolean>read(run.approver().browser().get(TEMPLATES).body(), "$.data.canCreate"))
                .as("modify-all implies create").isTrue();
    }

    private static String optional(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }
}
