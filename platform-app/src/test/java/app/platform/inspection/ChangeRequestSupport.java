package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the change requests share (Functional track FA5). A template that has been through its
 * approval and is published to the people of a department, a reader and a colleague in that department, an author who
 * owns the template, an approver, and short ways to ask, comment, answer and turn requests into a version. The words of
 * the solution are in these tests because they test the solution that ships.
 */
public abstract class ChangeRequestSupport extends TemplateSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    protected static final String REQUEST_OBJECT = "InspectionChangeRequest";
    protected static final String REVIEW_RECORDS = "/api/v1/objects/" + REQUEST_OBJECT + "/records";

    /**
     * A published template and the people around it.
     *
     * @param author owns the template and may change it
     * @param approver holds the Approver policy (view all, modify all), approved and published it
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
     * process for its change requests with one step: the approver of the world decides every request.
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

    /** The identifier of the first column of a version, as the browser sees it. */
    protected static String columnOf(TestBrowser browser, String template, int number) {
        Response read = version(browser, template, number);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return JsonPath.read(read.body(), "$.data.grid.columns[0].id");
    }

    // ---- asking and commenting ----

    /** The JSON of a comment; a null place is left out, and both left out means the template as a whole. */
    protected static String comment(String row, String column, String text) {
        return "{" + (row == null ? "" : "\"rowId\":\"" + row + "\",")
                + (column == null ? "" : "\"columnId\":\"" + column + "\",") + "\"text\":\"" + text + "\"}";
    }

    protected static Response ask(TestBrowser browser, String template, String title, int version,
            String... comments) {
        return browser.postJson(changes(template), "{\"title\":\"" + title + "\",\"versionNo\":" + version
                + ",\"comments\":[" + String.join(",", comments) + "]}");
    }

    /** Asks for a change that must be accepted and returns the identifier of the request. */
    protected static String asked(TestBrowser browser, String template, String title, int version,
            String... comments) {
        Response made = ask(browser, template, title, version, comments);
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    /** Asks for a change and has the approver of the world accept it; returns the identifier of the request. */
    protected static String acceptedRequest(World world, TestBrowser browser, String title, int version,
            String... comments) {
        String request = asked(browser, world.template(), title, version, comments);
        assertThat(accept(world, request).status()).isEqualTo(200);
        return request;
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

    /** The approver of the world accepts the request through its approval. */
    protected static Response accept(World world, String request) {
        return approve(world.approver().browser(), approvalOfRequest(world, request));
    }

    /** The approver of the world declines the request through its approval, with a reason. */
    protected static Response decline(World world, String request, String reason) {
        return decide(world.approver().browser(), approvalOfRequest(world, request), "reject", reason);
    }

    /** Puts a request back to accepted when its version was not used. */
    protected static Response reopen(TestBrowser browser, String template, String request) {
        return browser.postJson(changes(template) + "/" + request + "/reopen", "{}");
    }
    protected static Response turn(TestBrowser browser, String template, String... requests) {
        List<String> quoted = new ArrayList<>();
        for (String request : requests) {
            quoted.add("\"" + request + "\"");
        }
        return browser.postJson(changes(template) + "/turn-into-version", "{\"requestIds\":["
                + String.join(",", quoted) + "]}");
    }

    protected static String requestStatus(TestBrowser browser, String template, String request) {
        Response response = one(browser, template, request);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.status");
    }

    // ---- sheets ----

    /** The grid with its one named row taken out; the header band and the column stay. */
    protected static String withoutRows(String grid) {
        Map<String, Object> document = JSON.readValue(grid, new TypeReference<Map<String, Object>>() {
        });
        document.put("rows", new ArrayList<>());
        return JSON.writeValueAsString(document);
    }

    /** Takes the draft through approval to the end of the first step and returns the identifier of the request. */
    protected static String sendDraft(World world) {
        return submitted(world.author().browser(), world.template(), world.process());
    }

    /** Edits the draft so it differs from its source (one row fewer), then sends it. */
    protected static String editAndSend(World world, int draft) {
        Response read = version(world.author().browser(), world.template(), draft);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        Response saved = saveGrid(world.author().browser(), world.template(), draft, rowVersion(read),
                withoutRows(gridOf(read)));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        return sendDraft(world);
    }
}
