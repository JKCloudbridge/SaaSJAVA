package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;

/**
 * What the integration tests of the fill-ins share (Functional track FF1). A template with a real sheet (five columns
 * of
 * the five types, one of them required, two named rows and a cell the sheet fixes) that has been through its approval
 * and
 * is published to the people of a department, the people around it (an author, an approver, two fillers in the
 * department, a person who holds the Reviewer policy, a person who is only asked to review, and one who holds
 * nothing), an
 * approval process on the fill-in, and short ways to start, read, save, send and list. The words of the solution are in
 * these tests because they test the solution that ships.
 */
public abstract class FillSupport extends TemplateSupport {

    protected static final String FILL_RECORDS = "/api/v1/objects/" + FILL + "/records";
    protected static final String FILLS = "/api/v1/sheet-fills/" + FILL + "/";
    protected static final String FILL_INS = "/api/v1/fill-ins";

    /**
     * A published template and the people around it.
     *
     * @param author owns the template
     * @param approver holds the Approver policy: approved and published the template, sees every fill-in, may correct
     * one
     * @param filler holds the Filler policy and is in the department the template is published to
     * @param colleague holds the Filler policy and is in the same department
     * @param reviewer holds the Reviewer policy only: sees every fill-in and changes none
     * @param checker holds no policy but is named in the approval process of the fill-in, so has a task
     * @param outsider holds no policy of the solution at all
     * @param template the published template
     * @param templateProcess the approval process of the template
     * @param process the approval process of the fill-in
     */
    protected record World(Setting setting, Person author, Person approver, Person filler, Person colleague,
            Person reviewer, Person checker, Person outsider, String template, String templateProcess,
            String process) {
    }

    /** The sheet: five columns of the five types, two named rows (or none), and the cell {@code r1:d} fixed. */
    protected static String sheet(int dimension, String rowsMode) {
        String rows = dimension == 2
                ? "[{\"id\":\"r1\",\"label\":\"Fire exit\"},{\"id\":\"r2\",\"label\":\"Alarm\"}]" : "[]";
        String fixed = dimension == 2 ? ",\"r1:d\":{\"value\":\"Set by the sheet\"}" : "";
        return "{\"formatVersion\":1,\"dimension\":" + dimension + ",\"rowsMode\":\"" + rowsMode + "\",\"columns\":["
                + "{\"id\":\"t\",\"label\":\"Finding\",\"type\":\"text\",\"required\":true},"
                + "{\"id\":\"n\",\"label\":\"Reading\",\"type\":\"number\"},"
                + "{\"id\":\"d\",\"label\":\"Checked on\",\"type\":\"date\"},"
                + "{\"id\":\"p\",\"label\":\"Result\",\"type\":\"picklist\",\"picklist\":{\"values\":[\"Pass\","
                + "\"Fail\"]}},{\"id\":\"b\",\"label\":\"Done\",\"type\":\"boolean\"}],"
                + "\"headerRows\":[{\"id\":\"h1\"}],\"rows\":" + rows + ",\"cells\":{\"h1:t\":{\"value\":"
                + "\"Checklist\"}" + fixed + "},\"merges\":[]}";
    }

    /** A template whose first version is this sheet, saved as a draft; returns the identifier of the template. */
    protected static String templateWithColumns(TestBrowser author, int dimension, String rowsMode) {
        String template = template(author, "WI", "PRD");
        Response started = author.postJson(versions(template), "{\"dimension\":" + dimension + ",\"rowsMode\":\""
                + rowsMode + "\"}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        saveSheet(author, template, 1, sheet(dimension, rowsMode));
        return template;
    }

    /** Saves a sheet over the draft and checks that it was accepted. */
    protected static void saveSheet(TestBrowser author, String template, int number, String grid) {
        Response read = version(author, template, number);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        Response saved = saveGrid(author, template, number, rowVersion(read), grid);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }

    protected World world() {
        return world(2, "FIXED_AND_REPEATABLE");
    }

    /** A published template of this shape: approved, then published to the department of the fillers. */
    protected World world(int dimension, String rowsMode) {
        return world(dimension, rowsMode, true);
    }

    /**
     * As {@link #world(int, String)}; with {@code withFillProcess} false the organization has no approval process for
     * fill-ins (FA6A), so a fill-in is sent for good with Submit.
     */
    protected World world(int dimension, String rowsMode, boolean withFillProcess) {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person filler = holding(setting, "Filler");
        Person colleague = holding(setting, "Filler");
        Person reviewer = holding(setting, "Reviewer");
        Person checker = outsider(setting);
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), checker.member().membership(),
                "user");
        Person outsider = outsider(setting);
        String templateProcess = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String fillProcess = withFillProcess
                ? fillProcess(setting.admin(), "FillReview", step("Review", user(approver.userId()),
                        user(checker.userId())))
                : null;
        department(setting.admin(), "PLANT1", "PRD",
                group(setting.admin(), "dept-" + System.nanoTime(), filler, colleague));
        String template = templateWithColumns(author.browser(), dimension, rowsMode);
        assertThat(approve(approver.browser(), submitted(author.browser(), template, templateProcess)).status())
                .isEqualTo(200);
        assertThat(publish(approver.browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        return new World(setting, author, approver, filler, colleague, reviewer, checker, outsider, template,
                templateProcess, fillProcess);
    }

    /** Makes an approval process on the fill-in object and returns its API name. */
    protected static String fillProcess(TestBrowser admin, String name, String... steps) {
        Response created = admin.postJson(DEFINITIONS + "/" + FILL, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"description\":\"\",\"active\":true,\"entryCriteria\":null,\"allowSelfApproval\":false,"
                + "\"steps\":[" + String.join(",", steps) + "],\"outcomes\":null,\"fallback\":null}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    // ---- starting, reading, saving ----

    protected static Response startFill(TestBrowser browser, String template) {
        return browser.postJson(TEMPLATES + "/" + template + "/fill-ins", "{}");
    }

    /** Starts a fill-in that must be accepted and returns its identifier. */
    protected static String started(TestBrowser browser, String template) {
        Response response = startFill(browser, template);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.data.fillId");
    }

    protected static Response fillView(TestBrowser browser, String fill) {
        return browser.get(FILLS + fill);
    }

    protected static long fillVersion(Response view) {
        return JsonPath.<Number>read(view.body(), "$.data.fill.version").longValue();
    }

    protected static String fillStatus(TestBrowser browser, String fill) {
        Response view = fillView(browser, fill);
        assertThat(view.status()).as(view.body()).isEqualTo(200);
        return JsonPath.read(view.body(), "$.data.fill.status");
    }

    protected static String cells(String... rows) {
        return "{\"formatVersion\":1,\"rows\":[" + String.join(",", rows) + "]}";
    }

    protected static String row(String id, String values) {
        return "{\"id\":\"" + id + "\",\"cells\":{" + values + "}}";
    }

    protected static Response saveCells(TestBrowser browser, String fill, long version, String document) {
        return browser.request("PUT", FILLS + fill + "/cells?version=" + version, document);
    }

    /** Saves the cells of a fill-in as they are now, which must be accepted; returns the new row version. */
    protected static long saved(TestBrowser browser, String fill, String document) {
        Response view = fillView(browser, fill);
        assertThat(view.status()).as(view.body()).isEqualTo(200);
        Response saved = saveCells(browser, fill, fillVersion(view), document);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        return JsonPath.<Number>read(saved.body(), "$.data.version").longValue();
    }

    /** The cells of a complete fill-in of {@link #sheet}: both named rows with the required finding. */
    protected static String complete() {
        return cells(row("r1", "\"t\":\"Door open\",\"n\":\"12.5\",\"p\":\"Pass\",\"b\":true"),
                row("r2", "\"t\":\"Bell works\""));
    }

    // ---- sending and reviewing ----

    protected static Response sendFill(TestBrowser browser, String fill, String process) {
        return browser.postJson(FILL_RECORDS + "/" + fill + "/approval", "{\"process\":\"" + process
                + "\",\"comment\":null}");
    }

    /** Sends a fill-in that must be accepted and returns the identifier of the request. */
    protected static String sent(TestBrowser browser, String fill, String process) {
        Response response = sendFill(browser, fill, process);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.data.summary.id");
    }

    // ---- the list ----

    protected static List<String> listedFills(TestBrowser browser, String query) {
        Response page = browser.get(FILL_INS + (query.isEmpty() ? "" : "?" + query));
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].fillId");
    }

    protected static String headerStatus(TestBrowser browser, String fill) {
        return (String) headerField(browser, fill, "status");
    }

    protected static Object headerField(TestBrowser browser, String fill, String field) {
        try {
            return JsonPath.read(browser.get(FILL_RECORDS + "/" + fill).body(), "$.data.fields." + field);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    // ---- a second version of the template ----

    /**
     * Makes a new version the one in use and returns its number: a draft copied from the one in use, the label of the
     * first column changed so that the two differ, approved and published to the same department.
     */
    protected static int nextVersionInUse(World world) {
        int draft = newVersion(world.author().browser(), world.template());
        saveSheet(world.author().browser(), world.template(), draft,
                sheet(2, "FIXED_AND_REPEATABLE").replace("Finding", "Observation"));
        String request = submitted(world.author().browser(), world.template(), world.templateProcess());
        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status())
                .isEqualTo(200);
        return draft;
    }
}
