package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The number of a template (FA4, ADR-0176, ADR-0184, ADR-0188): the formats that count and the four that end in a typed
 * part, what the author still has to fill in, the start of the number shown before an approval, a clash found before it
 * can hurt and, if one slips through, refused by the approval with the decision undone, and two real application
 * instances numbering at once so that every number is given exactly once.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateNumberingIT extends TemplateSupport {

    private static TestApplication instanceB;

    @Autowired
    private app.platform.data.internal.BackfillDriver driver;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private record Run(Setting setting, Person author, Person approver, String process) {
    }

    private Run run() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        return new Run(setting, author, approver, process);
    }

    private String approvedNumber(Run run, String template) {
        String request = submitted(run.author().browser(), template, run.process());
        Response approved = approve(run.approver().browser(), request);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return number(run.setting().admin(), template);
    }

    // ---- the formats that count, and the four that end in a typed part ----

    @Test
    void aNewCategoryGetsItsDefaultNumberAtFirstApprovalAndLaterFormatChangesLeaveOldNumbersAlone() {
        Run run = run();
        String fields = "/api/v1/metadata/objects/InspectionTemplate/fields/";
        assertThat(run.setting().admin().postJson(fields + "category/values", "{\"value\":\"DEMO\"}")
                .status()).isEqualTo(200);
        assertThat(run.setting().admin().postJson(fields + "subCategory/values",
                "{\"value\":\"CHK\",\"controllingValues\":[\"DEMO\"]}").status()).isEqualTo(200);
        String first = templateWithSheet(run.author().browser(), "DEMO", "CHK");
        assertThat(approvedNumber(run, first)).isEqualTo("DEMO/CHK/1");
        Response changed = run.setting().admin().request("PUT", "/api/v1/numbering/document/formats",
                "{\"kind\":\"CATEGORY\",\"selectorValue\":\"DEMO\",\"version\":0,"
                        + "\"format\":\"{field:category}-{field:subCategory}-{seq:2}\"}",
                "Content-Type", "application/json");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        String second = templateWithSheet(run.author().browser(), "DEMO", "CHK");
        assertThat(approvedNumber(run, second)).isEqualTo("DEMO-CHK-02");
        assertThat(number(run.setting().admin(), first)).isEqualTo("DEMO/CHK/1");
    }

    @Test
    void everyCategoryOfTheFirstCustomerGetsItsNumberInItsFormat() {
        Run run = run();
        List<String[]> cases = List.of(new String[] {"IMS", null, "IMS/01"}, new String[] {"QSP", null, "QSP-01"},
                new String[] {"CORPQSP", null, "CORP-QSP-01"}, new String[] {"ESSP", null, "ESSP-01"},
                new String[] {"WI", "PRD", "WI/PRD/01"}, new String[] {"SOP", "QLT", "SOP/QLT/01"},
                new String[] {"F", "MNT", "F/MNT/01"}, new String[] {"FEHS", "STR", "F/EHS/STR/01"},
                new String[] {"CSF", "CUSA", "CSF/CUSA/01"}, new String[] {"PPT", "PRD", "PPT/PRD/01"},
                new String[] {"L", "QLT", "L/QLT/01"}, new String[] {"LEHS", "MNT", "L/EHS/MNT/01"});

        for (String[] one : cases) {
            String template = templateWithSheet(run.author().browser(), one[0], one[1]);
            assertThat(approvedNumber(run, template)).as(one[0]).isEqualTo(one[2]);
        }
    }

    @Test
    void theFourCategoriesWhoseLastPartIsTypedAreNumberedFromTheTypedPart() {
        Run run = run();
        String control = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"AB-12\"}");
        String flow = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"PFC\",\"subCategory\":\"QLT\",\"partCode\":\"X9\"}");
        String analysis = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"FMEA\",\"subCategory\":\"MNT\",\"partCode\":\"P_1\"}");
        String plan = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"BP\",\"planYear\":\"2026\"}");
        for (String template : List.of(control, flow, analysis, plan)) {
            fill(run.author().browser(), template, firstVersion(run.author().browser(), template));
        }

        assertThat(approvedNumber(run, control)).isEqualTo("CP/PRD/AB-12");
        assertThat(approvedNumber(run, flow)).isEqualTo("PFC/QLT/X9");
        assertThat(approvedNumber(run, analysis)).isEqualTo("FMEA/MNT/P_1");
        assertThat(approvedNumber(run, plan)).isEqualTo("BP/2026");
    }

    @Test
    void aMissingOrBadTypedPartIsRefusedAtTheFirstApprovalInWordsAndTheTemplateStaysInReview() {
        Run run = run();
        String noCode = templateWithSheet(run.author().browser(), "CP", "PRD");
        String badCode = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"has space\"}");
        fill(run.author().browser(), badCode, firstVersion(run.author().browser(), badCode));
        String noYear = templateWithSheet(run.author().browser(), "BP", null);
        String badYear = unnumberedForBackstop(run, "{\"name\":\"" + newName()
                + "\",\"category\":\"BP\",\"planYear\":\"26\"}");
        fill(run.author().browser(), badYear, firstVersion(run.author().browser(), badYear));
        List<String> templates = List.of(noCode, badCode, noYear, badYear);
        // The author is not asked for the identity: every one of them is sent without a complaint.
        List<String> requests = new ArrayList<>();
        for (String template : templates) {
            requests.add(submitted(run.author().browser(), template, run.process()));
        }

        List<Response> refusals = new ArrayList<>();
        for (String request : requests) {
            refusals.add(approve(run.approver().browser(), request));
        }

        assertThat(message(refusals.get(0))).startsWith("Fill in the part code").contains("then approve again");
        assertThat(message(refusals.get(1))).startsWith("Fill in the part code");
        assertThat(message(refusals.get(2))).startsWith("Fill in the plan year");
        assertThat(message(refusals.get(3))).startsWith("The plan year is four digits");
        for (Response refused : refusals) {
            assertThat(refused.status()).isEqualTo(409);
            assertThat(code(refused)).isEqualTo("CONFLICT");
        }
        for (int i = 0; i < templates.size(); i++) {
            assertThat(statuses(run.author().browser(), templates.get(i))).containsExactly("IN_REVIEW");
            assertThat(statusOf(run.approver().browser(), requests.get(i))).isEqualTo("PENDING");
            assertThat(number(run.setting().admin(), templates.get(i))).isNull();
        }
        assertThat(message(refusals.get(0))).as("the words never repeat what was typed").doesNotContain("has space");
    }

    @Test
    void aTypedPartFormatWithoutItsSubCategoryIsNeverNumberedByTheCountedDefault() {
        Run run = run();
        String template = unnumberedForBackstop(run, "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"partCode\":\"AB-12\"}");
        fill(run.author().browser(), template, firstVersion(run.author().browser(), template));
        String request = submitted(run.author().browser(), template, run.process());

        Response refused = approve(run.approver().browser(), request);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(number(run.setting().admin(), template)).as("no CP/1 from the category default").isNull();
    }

    @Test
    void theStartOfTheNumberIsShownBeforeTheFirstApprovalNeverTheCounterAndNothingAfterIt() {
        Run run = run();
        String counted = templateWithSheet(run.author().browser(), "WI", "PRD");
        String typed = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"AB-12\"}");
        fill(run.author().browser(), typed, firstVersion(run.author().browser(), typed));

        Response start = lifecycle(run.author().browser(), counted);
        Response whole = lifecycle(run.author().browser(), typed);

        assertThat(optional(start.body(), "$.data.numberPreview")).isNull();
        assertThat(optional(whole.body(), "$.data.numberPreview")).isNull();
        assertThat(number(run.setting().admin(), counted)).isEqualTo("WI/PRD/01");
        assertThat(number(run.setting().admin(), typed)).isEqualTo("CP/PRD/AB-12");
        assertThat(approvedNumber(run, counted)).isEqualTo("WI/PRD/01");
        assertThat(optional(lifecycle(run.author().browser(), counted).body(), "$.data.numberPreview")).isNull();
        // Looking at the preview took no number: the first one given is still the first.
        assertThat(approvedNumber(run, templateWithSheet(run.author().browser(), "WI", "PRD"))).isEqualTo("WI/PRD/02");
    }

    private static String optional(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    // ---- a clash ----

    @Test
    void aTypedNumberAnotherTemplateAlreadyHasIsRefusedAtSaveWithWords() {
        Run run = run();
        String first = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), first, firstVersion(run.author().browser(), first));
        assertThat(approvedNumber(run, first)).isEqualTo("CP/PRD/SAME");
        Response clash = run.author().browser().postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}}");
        assertThat(clash.status()).isEqualTo(400);
        assertThat(clash.body()).doesNotContain("SAME");
    }

    @Test
    void aClashFoundOnlyAtTheApprovalRefusesItUndoesTheDecisionAndCanBeFixedByTheApprover() {
        Run run = run();
        String first = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), first, firstVersion(run.author().browser(), first));
        String second = unnumberedForBackstop(run, "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), second, firstVersion(run.author().browser(), second));
        // The database enforces uniqueness of the number only once the background work has filled its copy and set the
        // constraint; a fresh organization has not had that yet, so this test drives it and waits for it.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).until(() -> {
            driver.workOn(run.setting().organization().id());
            List<String> states = JsonPath.read(run.setting().admin()
                    .get("/api/v1/objects/InspectionTemplate/index-status").body(), "$.data.uniqueness[*].state");
            return !states.isEmpty() && states.stream().allMatch("ENFORCED"::equals);
        });
        // Simulate an older import with complete parts but no number, to exercise the approval backstop.
        String requestOne = submitted(run.author().browser(), first, run.process());
        String requestTwo = submitted(run.author().browser(), second, run.process());
        assertThat(approve(run.approver().browser(), requestOne).status()).isEqualTo(200);

        Response refused = approve(run.approver().browser(), requestTwo);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(message(refused)).startsWith("Another template already has the number this one would get");
        assertThat(statusOf(run.approver().browser(), requestTwo)).as("the decision was undone").isEqualTo("PENDING");
        assertThat(number(run.setting().admin(), second)).isNull();
        assertThat(statuses(run.author().browser(), second)).containsExactly("IN_REVIEW");
        assertThat(audited(run.setting().organization(), "approval.hook.refused")).isPositive();
        // The approver, who may correct a template in review, changes the part code and approves.
        long version = JsonPath.<Number>read(run.approver().browser().get(RECORDS + "/" + second).body(),
                "$.data.version").longValue();
        Response corrected = run.approver().browser().request("PATCH", RECORDS + "/" + second,
                "{\"fields\":{\"partCode\":\"OTHER\"},\"version\":" + version + "}");
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(approve(run.approver().browser(), requestTwo).status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), second)).isEqualTo("CP/PRD/OTHER");
    }

    // ---- two real instances numbering at once ----

    @Test
    void twoInstancesNumberingOneScopeAtOnceGiveEveryNumberExactlyOnce() throws Exception {
        Setting setting = setting();
        Person author = author(setting);
        int count = 8;
        List<Person> approvers = new ArrayList<>();
        List<String> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Person person = approver(setting);
            approvers.add(person);
            users.add(user(person.userId()));
        }
        // Every approver is asked for every template; each one approves a template of their own, half on each instance.
        String process = process(setting.admin(), "Race", step("Only", users.toArray(String[]::new)));
        List<String> templates = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String template = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            fill(author.browser(), template, firstVersion(author.browser(), template));
            templates.add(template);
            requests.add(submitted(author.browser(), template, process));
        }
        List<TestBrowser> browsers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            browsers.add(i % 2 == 0 ? approvers.get(i).browser() : TestOrganizations.signedIn(instanceB.port(),
                    setting.organization().host(), approvers.get(i).member().person()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> done = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                TestBrowser browser = browsers.get(i);
                String request = requests.get(i);
                String template = templates.get(i);
                Callable<Integer> work = () -> {
                    long version = JsonPath.<Number>read(browser.get(RECORDS + "/" + template).body(),
                            "$.data.version").longValue();
                    Response saved = browser.request("PATCH", RECORDS + "/" + template,
                            "{\"version\":" + version + ",\"fields\":{\"category\":\"WI\",\"subCategory\":\"PRD\"}}");
                    assertThat(saved.status()).as(saved.body()).isEqualTo(200);
                    return approve(browser, request).status();
                };
                done.add(pool.submit(work));
            }
            for (Future<Integer> one : done) {
                assertThat(one.get(120, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        TreeSet<String> numbers = new TreeSet<>();
        for (String template : templates) {
            numbers.add(number(setting.admin(), template));
        }
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            expected.add("WI/PRD/" + String.format("%02d", i));
        }
        assertThat(numbers).containsExactlyElementsOf(expected);
        assertThat(scalar(setting.organization(), "select next_value from number_series_counter "
                + "where series_key = 'document' and scope_key = 'WI/PRD'")).isEqualTo(count + 1);
    }

    /** A pre-feature imported header, deliberately bypassing Save to test the documented approval backstop. */
    private static String unnumberedForBackstop(Run run, String fields) {
        String id = template(run.author().browser(), "{\"name\":\"" + newName() + "\"}");
        try {
            app.platform.testsupport.tenancy.TenantFixtures.asTenant(run.setting().organization().id(), connection ->
                    app.platform.testsupport.tenancy.TenantFixtures.update(connection,
                            "update object_record set payload = payload || cast(? as jsonb), "
                                    + "version = version + 1, updated_by = created_by where id = ?",
                            fields, java.util.UUID.fromString(id)));
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not prepare an imported template", exception);
        }
        return id;
    }
}
