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
        String noSub = templateWithSheet(run.author().browser(), "WI", null);
        String noYear = templateWithSheet(run.author().browser(), "BP", null);
        String badYear = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"BP\",\"planYear\":\"26\"}");
        fill(run.author().browser(), badYear, firstVersion(run.author().browser(), badYear));
        List<String> templates = List.of(noCode, badCode, noSub, noYear, badYear);
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
        assertThat(message(refusals.get(2))).isEqualTo("Fill in the identity sub-category (Edit), then approve again: "
                + "its number is made from it.");
        assertThat(message(refusals.get(3))).startsWith("Fill in the plan year");
        assertThat(message(refusals.get(4))).startsWith("The plan year is four digits");
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
    void theStartOfTheNumberIsShownBeforeTheFirstApprovalNeverTheCounterAndNothingAfterIt() {
        Run run = run();
        String counted = templateWithSheet(run.author().browser(), "WI", "PRD");
        String typed = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"AB-12\"}");
        fill(run.author().browser(), typed, firstVersion(run.author().browser(), typed));

        Response start = lifecycle(run.author().browser(), counted);
        Response whole = lifecycle(run.author().browser(), typed);

        assertThat(JsonPath.<String>read(start.body(), "$.data.numberPreview")).isEqualTo("WI/PRD/");
        assertThat(JsonPath.<Boolean>read(start.body(), "$.data.numberPreviewComplete")).isFalse();
        assertThat(JsonPath.<String>read(whole.body(), "$.data.numberPreview")).isEqualTo("CP/PRD/AB-12");
        assertThat(JsonPath.<Boolean>read(whole.body(), "$.data.numberPreviewComplete")).isTrue();
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
    void aTypedNumberAnotherTemplateAlreadyHasIsRefusedAtTheFirstApprovalWithWords() {
        Run run = run();
        String first = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), first, firstVersion(run.author().browser(), first));
        String second = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), second, firstVersion(run.author().browser(), second));
        assertThat(approvedNumber(run, first)).isEqualTo("CP/PRD/SAME");
        String request = submitted(run.author().browser(), second, run.process());

        Response clash = approve(run.approver().browser(), request);

        assertThat(clash.status()).isEqualTo(409);
        assertThat(message(clash)).startsWith("Another template already has the number this one would get");
        assertThat(clash.body()).doesNotContain("SAME");
        assertThat(statuses(run.author().browser(), second)).containsExactly("IN_REVIEW");
        assertThat(statusOf(run.approver().browser(), request)).isEqualTo("PENDING");
    }

    @Test
    void aClashFoundOnlyAtTheApprovalRefusesItUndoesTheDecisionAndCanBeFixedByTheApprover() {
        Run run = run();
        String first = template(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"category\":\"CP\",\"subCategory\":\"PRD\",\"partCode\":\"SAME\"}");
        fill(run.author().browser(), first, firstVersion(run.author().browser(), first));
        String second = template(run.author().browser(), "{\"name\":\"" + newName()
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
        // Both are on their way before either has its number: the submit cannot see the clash yet.
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
            String template = templateWithSheet(author.browser(), "WI", "PRD");
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
                Callable<Integer> work = () -> approve(browser, request).status();
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
}
