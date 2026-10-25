package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the change requests share (Functional track FA5; the controlled change of FA8,
 * ADR-0202).
 * A template that has been through its approval and is published to the people of a department, a reader and a
 * colleague
 * in that department, an author who owns the template, an approver, and short ways to ask, comment, make the revised
 * copy,
 * complete the register and give the last approval. The words of the solution are in these tests because they test the
 * solution that ships.
 */
public abstract class ChangeRequestSupport extends TemplateSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger NUMBERS = new AtomicInteger();

    protected static final String REQUEST_OBJECT = "InspectionChangeRequest";
    protected static final String REVIEW_RECORDS = "/api/v1/objects/" + REQUEST_OBJECT + "/records";
    /** The window of the last approval: a location, its department, and no option. */
    protected static final String PUBLISH_CHOICE = choices("location=PLANT1", "department=PLANT1/PRD");

    /**
     * A published template and the people around it.
     *
     * @param author owns the template and may change it
     * @param approver holds the Approver and Publisher policies, approved and published it, and decides the requests
     * @param reader is in the department it is published to and asks for changes
     * @param colleague is in the same department and may read the template, but made no request
     * @param outsider holds no policy of the solution at all
     * @param column the identifier of the one column of the first version
     */
    protected record World(Setting setting, Person author, Person approver, Person reader, Person colleague,
            Person outsider, String template, String process, String column) {
    }

    protected static String changes(String template) {
        return TEMPLATES + "/" + template + "/change-requests";
    }

    protected static String groupApprover(String groupId) {
        return "{\"kind\":\"GROUP\",\"value\":\"" + groupId + "\"}";
    }

    /** A step that asks the approver for a choice (the audience Corporate QMS suggests). */
    protected static String askingStep(String name, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"ANY\",\"approvers\":["
                + String.join(",", approvers) + "],\"asksForChoice\":true}";
    }

    /** Makes a process on the shipped object that gives requests to the approval engine; returns its API name. */
    protected static String reviewProcess(TestBrowser admin, String name, String... steps) {
        Response created = admin.postJson(DEFINITIONS + "/" + REQUEST_OBJECT, "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"description\":\"\",\"active\":true,\"entryCriteria\":null,"
                + "\"allowSelfApproval\":false,\"steps\":[" + String.join(",", steps)
                + "],\"outcomes\":null,\"fallback\":null}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    /** The identifier of the review record of a request, from the answer that made it or showed it. */
    protected static String reviewIdOf(Response request) {
        return JsonPath.read(request.body(), "$.data.reviewId");
    }

    protected static Response approvalPanel(TestBrowser browser, String reviewId) {
        return browser.get(REVIEW_RECORDS + "/" + reviewId + "/approval");
    }

    /** The identifier of the approval request behind a review record, as the browser sees it. */
    protected static String approvalOf(TestBrowser browser, String reviewId) {
        Response panel = approvalPanel(browser, reviewId);
        assertThat(panel.status()).as(panel.body()).isEqualTo(200);
        return JsonPath.read(panel.body(), "$.data.current.summary.id");
    }

    /**
     * A template that is approved and published to a department whose group holds the reader and the colleague, and a
     * process for its change requests with one step: the approver of the world decides every request, so the one step
     * is
     * also the last, which publishes.
     */
    protected World world() {
        return world(true);
    }

    /** As {@link #world()}; without the process for change requests when a test makes its own. */
    protected World world(boolean withReviewProcess) {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        Person colleague = reader(setting);
        Person outsider = outsider(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD",
                group(setting.admin(), "dept-" + System.nanoTime(), reader, colleague));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        assertThat(approve(approver.browser(), submitted(author.browser(), template, process)).status())
                .isEqualTo(200);
        assertThat(publish(approver.browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        if (withReviewProcess) {
            reviewProcess(setting.admin(), "ChangeReview" + System.nanoTime(), step("Decide", user(approver.userId())));
        }
        return new World(setting, author, approver, reader, colleague, outsider, template, process,
                columnOf(reader.browser(), template, 1));
    }

    /**
     * One more template of the world, approved and published to the same department. Only one change may be in progress
     * on a template (FA8), so a test that needs several requests at once needs several templates.
     */
    protected static String anotherPublished(World world) {
        String template = templateWithSheet(world.author().browser(), "WI", "PRD");
        assertThat(approve(world.approver().browser(), submitted(world.author().browser(), template,
                world.process())).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        return template;
    }

    /** The same people looking at another template of the world. */
    protected static World on(World world, String template) {
        return new World(world.setting(), world.author(), world.approver(), world.reader(), world.colleague(),
                world.outsider(), template, world.process(), world.column());
    }

    /** The identifier of the first column of a version, as the browser sees it. */
    protected static String columnOf(TestBrowser browser, String template, int number) {
        Response read = version(browser, template, number);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return JsonPath.read(read.body(), "$.data.grid.columns[0].id");
    }

    // ---- asking and commenting ----

    /** The JSON of a note on a place; a null place is left out, and both left out means the template as a whole. */
    protected static String comment(String row, String column, String text) {
        return "{" + (row == null ? "" : "\"rowId\":\"" + row + "\",")
                + (column == null ? "" : "\"columnId\":\"" + column + "\",") + "\"text\":\"" + text + "\"}";
    }

    /** The JSON of the register form a requester types: a new request number each time, and what is asked. */
    protected static String register(String changeRequested) {
        return register("DCN-" + NUMBERS.incrementAndGet(), changeRequested);
    }

    protected static String register(String requestNo, String changeRequested) {
        return "{\"requestNo\":\"" + requestNo + "\",\"changeRequested\":\"" + changeRequested + "\"}";
    }

    /** Asks for a change: the register carries {@code change} as what is asked; the places are optional notes. */
    protected static Response ask(TestBrowser browser, String template, String change, int version,
            String... comments) {
        return askWith(browser, template, register(change), version, comments);
    }

    protected static Response askWith(TestBrowser browser, String template, String registerJson, int version,
            String... comments) {
        return browser.postJson(changes(template), "{\"versionNo\":" + version + ",\"register\":" + registerJson
                + ",\"comments\":[" + String.join(",", comments) + "]}");
    }

    /** Asks for a change that must be accepted and returns the identifier of the request. */
    protected static String asked(TestBrowser browser, String template, String change, int version,
            String... comments) {
        Response made = ask(browser, template, change, version, comments);
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    protected static Response list(TestBrowser browser, String template) {
        return browser.get(changes(template));
    }

    protected static List<String> listedRequests(TestBrowser browser, String template) {
        Response response = list(browser, template);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.requests[*].id");
    }

    protected static Response one(TestBrowser browser, String template, String request) {
        return browser.get(changes(template) + "/" + request);
    }

    protected static Response add(TestBrowser browser, String template, String request, String comment) {
        return browser.postJson(changes(template) + "/" + request + "/comments", comment);
    }

    /** The approval of a request, as the approver of the world sees it. */
    protected static String approvalOfRequest(World world, String request) {
        Response shown = one(world.approver().browser(), world.template(), request);
        assertThat(shown.status()).as(shown.body()).isEqualTo(200);
        return approvalOf(world.approver().browser(), reviewIdOf(shown));
    }

    /** The approver of the world declines the request through its approval, with a reason. */
    protected static Response decline(World world, String request, String reason) {
        return decide(world.approver().browser(), approvalOfRequest(world, request), "reject", reason);
    }

    protected static String requestStatus(TestBrowser browser, String template, String request) {
        Response response = one(browser, template, request);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.status");
    }

    // ---- the revised copy, the register and the last approval ----

    /** The JSON of a list of choices from pairs written {@code key=value}. */
    protected static String choices(String... pairs) {
        List<String> list = new ArrayList<>();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            list.add("{\"key\":\"" + pair.substring(0, equals) + "\",\"value\":\"" + pair.substring(equals + 1)
                    + "\"}");
        }
        return "[" + String.join(",", list) + "]";
    }

    /** Approves a task with the choice the step asks for. */
    protected static Response approveWith(TestBrowser browser, String approval, String choicesJson) {
        return browser.postJson(INSTANCES + "/" + approval + "/approve", "{\"version\":"
                + instanceVersion(browser, approval) + ",\"comment\":null,\"choices\":" + choicesJson + "}");
    }

    /** Makes the revised copy of the sheet for a request. */
    protected static Response createCopy(TestBrowser browser, String template, String request) {
        return browser.postJson(changes(template) + "/" + request + "/revised-copy", "{}");
    }

    /** Makes the revised copy, which must succeed, and returns its version number. */
    protected static int copied(TestBrowser browser, String template, String request) {
        Response made = createCopy(browser, template, request);
        assertThat(made.status()).as(made.body()).isEqualTo(200);
        return JsonPath.<Integer>read(made.body(), "$.data.resultingVersion");
    }

    /** Changes the revised copy the way a reviewer does: one row fewer. */
    protected static void changeCopy(TestBrowser browser, String template, int copy) {
        Response read = version(browser, template, copy);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        Response saved = saveGrid(browser, template, copy, rowVersion(read), withoutRows(gridOf(read)));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }

    protected static Response registerOf(TestBrowser browser, String template, String request) {
        return browser.get(changes(template) + "/" + request + "/register");
    }

    /** Completes the bottom of the register; {@code body} holds the boxes, the version is added. */
    protected static Response completeRegister(TestBrowser browser, String template, String request, long version,
            String boxes) {
        return browser.request("PUT", changes(template) + "/" + request + "/register",
                "{\"version\":" + version + (boxes.isEmpty() ? "" : "," + boxes) + "}");
    }

    protected static long registerVersion(TestBrowser browser, String template, String request) {
        Response read = registerOf(browser, template, request);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return JsonPath.<Number>read(read.body(), "$.data.version").longValue();
    }

    /** A value of an answer, or null when the answer does not carry it (a missing value is not sent). */
    protected static Object at(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    // ---- sheets ----

    /** The grid with its one named row taken out; the header band and the column stay. */
    protected static String withoutRows(String grid) {
        Map<String, Object> document = JSON.readValue(grid, new TypeReference<Map<String, Object>>() {
        });
        document.put("rows", new ArrayList<>());
        return JSON.writeValueAsString(document);
    }
}
