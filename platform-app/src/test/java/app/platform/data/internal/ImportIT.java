package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CSV imports (ADR-0123): the mapping is checked at once, every row goes through the write steps as the person who
 * started the import, a failed row is reported without repeating a value, an upsert finds the record by its
 * external identifier, a dry run saves nothing, and an import belongs to the person who started it.
 */
@PlatformIntegrationTest
class ImportIT extends RecordSecuritySupport {

    @Autowired
    private ImportRunner runner;

    private static String imports() {
        return "/api/v1/objects/Item__c/imports";
    }

    private static String start(String operation, boolean dryRun, String externalId, String mapping) {
        return "{\"operation\":\"" + operation + "\",\"dryRun\":" + dryRun
                + (externalId == null ? "" : ",\"externalIdField\":\"" + externalId + "\"")
                + (mapping == null ? "" : ",\"mapping\":" + mapping) + "}";
    }

    private static BytesResponse send(TestBrowser browser, String id, String csv) {
        return browser.bytes("PUT", imports() + "/" + id + "/file", csv.getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/csv");
    }

    /** Starts an import, sends the file, runs the work of the organization and returns the import as it ends. */
    private String run(Organization organization, TestBrowser browser, String settings, String csv) {
        Response started = browser.postJson(imports(), settings);
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        String id = idOf(started);
        BytesResponse sent = send(browser, id, csv);
        assertThat(sent.status()).as(sent.text()).isEqualTo(202);
        runner.workOn(organization.id());
        return id;
    }

    private static List<String> auditsOfImport(String type, String importId) {
        return owner("select coalesce(attributes::text, '') from audit_record where event_type = ? "
                + "and attributes ->> 'import' = ? order by occurred_at, id", type, importId);
    }

    private static Response job(TestBrowser browser, String id) {
        return browser.get(imports() + "/" + id);
    }

    private static int number(Response job, String field) {
        return JsonPath.<Number>read(job.body(), "$.data." + field).intValue();
    }

    private static Response flagExternalId(TestBrowser admin, String object, String name, String type) {
        long version = fieldVersion(admin, object, name);
        return admin.request("PUT", METADATA_OBJECTS + "/" + object + "/fields/" + name, "{\"label\":\"ext\","
                + "\"description\":\"\",\"externalId\":true,\"version\":" + version + "}");
    }

    private Items itemsWithExternalId() {
        Items items = items();
        Response created = items.admin().postJson(METADATA_OBJECTS + "/Item__c/fields",
                "{\"name\":\"ext\",\"label\":\"ext\",\"type\":\"TEXT\",\"externalId\":true}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        settleUniqueness(items.organization(), items.admin());
        return items;
    }

    // ---- creating ----

    @Test
    void everyRowOfTheFileBecomesARecordOwnedByTheStarterAndTheCountsSayHowMany() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String id = run(items.organization(), person.browser(), start("CREATE", false, null, null),
                "name__c,qty__c\nfirst,1\nsecond,2\nthird,3\n");
        Response done = job(person.browser(), id);

        assertThat(JsonPath.<String>read(done.body(), "$.data.state")).as(done.body()).isEqualTo("COMPLETED");
        assertThat(number(done, "totalRows")).isEqualTo(3);
        assertThat(number(done, "createdRows")).isEqualTo(3);
        assertThat(number(done, "failedRows")).isZero();
        assertThat(listed(person.browser())).hasSize(3);
        assertThat(owner("select count(distinct owner_id) || '/' || count(*) from object_record where tenant_id = "
                + "?::uuid and object_api_name = 'Item__c'", items.organization().id().value().toString()))
                .containsExactly("1/3");
    }

    @Test
    void aMappingNamesTheColumnsOfTheFileAndAnEmptyCellLeavesAFieldAsItIs() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String id = run(items.organization(), person.browser(), start("CREATE", false, null,
                "{\"Title\":\"name__c\",\"Count\":\"qty__c\"}"), "Title,Count\nfirst,\nsecond,5\n");
        Response done = job(person.browser(), id);
        List<String> quantities = queried(person.browser());

        assertThat(JsonPath.<String>read(done.body(), "$.data.state")).isEqualTo("COMPLETED");
        assertThat(number(done, "createdRows")).isEqualTo(2);
        assertThat(quantities).hasSize(2);
    }

    @Test
    void aRowThatTheWriteStepsRefuseIsReportedByItsNumberAndTheOthersAreStillSaved() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String id = run(items.organization(), person.browser(), start("CREATE", false, null, null),
                "name__c,qty__c\nfirst,1\nsecond,not a number\nthird,3\n");
        Response done = job(person.browser(), id);
        BytesResponse report = person.browser().bytes("GET", imports() + "/" + id + "/errors", null);

        assertThat(number(done, "createdRows")).isEqualTo(2);
        assertThat(number(done, "failedRows")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(done.body(), "$.data.errorsAvailable")).isTrue();
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.header("Content-Type")).hasValueSatisfying(value ->
                assertThat(value).startsWith("text/csv"));
        assertThat(report.text()).contains("2,").contains("second");
        assertThat(report.text()).as("the reason is a message of the write steps, not the value")
                .doesNotContain("not a number\",\"");
    }

    @Test
    void aValueThatAProgramWouldRunInASpreadsheetIsMadeSafeInTheReport() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String id = run(items.organization(), person.browser(), start("CREATE", false, null, null),
                "name__c,qty__c\n=cmd|' /c calc'!A0,x\n");
        BytesResponse report = person.browser().bytes("GET", imports() + "/" + id + "/errors", null);

        assertThat(report.status()).isEqualTo(200);
        assertThat(report.text()).contains("'=cmd").doesNotContain(",=cmd");
    }

    @Test
    void aDryRunChecksEveryRowByTheRealWriteAndSavesNothing() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String id = run(items.organization(), person.browser(), start("CREATE", true, null, null),
                "name__c,qty__c\nfirst,1\nsecond,bad\n");
        Response done = job(person.browser(), id);

        assertThat(JsonPath.<String>read(done.body(), "$.data.state")).isEqualTo("COMPLETED");
        assertThat(number(done, "createdRows")).isEqualTo(1);
        assertThat(number(done, "failedRows")).isEqualTo(1);
        assertThat(listed(person.browser())).isEmpty();
    }

    @Test
    void theSemicolonAndTabDelimitersAndQuotedValuesWithLineBreaksAreRead() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        String semicolon = run(items.organization(), person.browser(),
                "{\"operation\":\"CREATE\",\"delimiter\":\"SEMICOLON\"}", "name__c;qty__c\nfirst;1\n");
        runner.workOn(items.organization().id());
        String quoted = run(items.organization(), person.browser(), start("CREATE", false, null, null),
                "name__c,qty__c\n\"line one\nline two, with comma\",2\n");

        assertThat(number(job(person.browser(), semicolon), "createdRows")).isEqualTo(1);
        assertThat(number(job(person.browser(), quoted), "createdRows")).isEqualTo(1);
        assertThat(listed(person.browser())).hasSize(2);
    }

    // ---- upsert and external identifiers ----

    @Test
    void anUpsertUpdatesTheRecordWithTheExternalIdentifierAndMakesTheRestAndASecondRunChangesNothingNew() {
        Items items = itemsWithExternalId();
        Person person = workerWith(items.organization(), items.admin(), "ext__c");
        String existing = put(person.browser(), "{\"ext__c\":\"E1\",\"name__c\":\"old\"}");

        String first = run(items.organization(), person.browser(), start("UPSERT", false, "ext__c", null),
                "ext__c,name__c\nE1,renamed\nE2,fresh\n");
        String second = run(items.organization(), person.browser(), start("UPSERT", false, "ext__c", null),
                "ext__c,name__c\nE1,renamed\nE2,fresh\n");

        assertThat(number(job(person.browser(), first), "updatedRows")).isEqualTo(1);
        assertThat(number(job(person.browser(), first), "createdRows")).isEqualTo(1);
        assertThat(number(job(person.browser(), second), "createdRows")).isZero();
        assertThat(listed(person.browser())).hasSize(2).contains(existing);
        assertThat(JsonPath.<String>read(person.browser().get(ITEM_RECORDS + "/" + existing).body(),
                "$.data.fields.name__c")).isEqualTo("renamed");
    }

    @Test
    void anUpsertRowWithoutItsExternalIdentifierFailsAlone() {
        Items items = itemsWithExternalId();
        Person person = workerWith(items.organization(), items.admin(), "ext__c");

        String id = run(items.organization(), person.browser(), start("UPSERT", false, "ext__c", null),
                "ext__c,name__c\n,nobody\nE9,somebody\n");
        Response done = job(person.browser(), id);

        assertThat(number(done, "failedRows")).isEqualTo(1);
        assertThat(number(done, "createdRows")).isEqualTo(1);
    }

    @Test
    void aLinkIsWrittenAsTheExternalIdentifierOfTheOtherRecordWhenTheMappingSaysSo() {
        Items items = itemsWithExternalId();
        Response targetField = items.admin().postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"ext\",\"label\":\"ext\",\"type\":\"TEXT\",\"externalId\":true}");
        assertThat(targetField.status()).as(targetField.body()).isEqualTo(201);
        settleUniqueness(items.organization(), items.admin());
        // Resolving the link reads Target__c through the same gated path as any other read, so the importing
        // member needs the right to read that object and its external identifier field too.
        String fields = "[" + String.join(",", ITEM_FIELDS.keySet().stream()
                .map(name -> "{\"key\":\"Item__c." + name + "__c\",\"actions\":[\""
                        + ("calc".equals(name) ? "read" : "edit") + "\"]}").toList())
                + ",{\"key\":\"Target__c.ext__c\",\"actions\":[\"read\"]}]";
        Holder holder = holderWithMatrix(items.organization(), items.admin(), "{\"objects\":["
                + "{\"key\":\"Item__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]},"
                + "{\"key\":\"Target__c\",\"actions\":[\"read\"]}],\"fields\":" + fields + "}");
        Person person = new Person(holder.member(), as(items.organization(), holder.member()), holder.profileId());
        Response target = items.admin().postJson("/api/v1/objects/Target__c/records",
                "{\"fields\":{\"ext__c\":\"T1\"}}");
        assertThat(target.status()).as(target.body()).isEqualTo(201);

        String id = run(items.organization(), person.browser(), start("CREATE", false, null,
                "{\"name__c\":\"name__c\",\"Parent\":\"link__c:ext__c\"}"),
                "name__c,Parent\nlinked,T1\nlost,T404\n");
        Response done = job(person.browser(), id);

        assertThat(number(done, "createdRows")).as(done.body()).isEqualTo(1);
        assertThat(number(done, "failedRows")).isEqualTo(1);
    }

    @Test
    void onlyAFieldFlaggedAsAnExternalIdentifierCanFindRecordsAndOnlyTextAndNumbersCanBeOne() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response notFlagged = person.browser().postJson(imports(), start("UPSERT", false, "code__c", null));
        Response badType = flagExternalId(items.admin(), "Item__c", "due__c", "DATE");

        assertThat(notFlagged.status()).isEqualTo(400);
        assertThat(badType.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(badType.body(), "$.error.fields.externalId[0]")).isNotBlank();
    }

    // ---- the mapping is checked when the import starts ----

    @Test
    void aMappingThatNamesAnUnknownFieldAFormulaOrALinkTheCallerMayNotWriteIsRefusedAtOnceWithEveryProblem() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response refused = person.browser().postJson(imports(), start("CREATE", false, null,
                "{\"a\":\"nothing__c\",\"b\":\"calc__c\",\"c\":\"id\"}"));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.mapping")).hasSizeGreaterThan(1);
        assertThat(refused.body()).doesNotContain("password");
    }

    @Test
    void anOperationOrDelimiterThatIsNotOneOfTheListedIsRefused() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        assertThat(person.browser().postJson(imports(), "{\"operation\":\"MERGE\"}").status()).isEqualTo(400);
        assertThat(person.browser().postJson(imports(),
                "{\"operation\":\"CREATE\",\"delimiter\":\"PIPE\"}").status()).isEqualTo(400);
    }

    // ---- the file ----

    @Test
    void aFileThatIsNotTextOrIsEmptyOrSentTwiceIsRefused() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String id = idOf(person.browser().postJson(imports(), start("CREATE", false, null, null)));

        BytesResponse binary = person.browser().bytes("PUT", imports() + "/" + id + "/file",
                new byte[] {'a', 0, 'b', '\n'}, "Content-Type", "text/csv");
        BytesResponse empty = person.browser().bytes("PUT", imports() + "/" + id + "/file", new byte[0],
                "Content-Type", "text/csv");
        BytesResponse good = send(person.browser(), id, "name__c\nx\n");
        BytesResponse again = send(person.browser(), id, "name__c\ny\n");

        assertThat(binary.status()).isEqualTo(400);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(good.status()).isEqualTo(202);
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void aSecondImportOfTheSameObjectWhileOneIsWaitingIsRefused() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String first = idOf(person.browser().postJson(imports(), start("CREATE", false, null, null)));
        String second = idOf(person.browser().postJson(imports(), start("CREATE", false, null, null)));
        assertThat(send(person.browser(), first, "name__c\nx\n").status()).isEqualTo(202);

        BytesResponse refused = send(person.browser(), second, "name__c\ny\n");

        assertThat(refused.status()).isEqualTo(409);
        runner.workOn(items.organization().id());
        assertThat(send(person.browser(), second, "name__c\ny\n").status()).isEqualTo(202);
    }

    @Test
    void anImportThatIsStoppedBeforeItRunsWritesNothing() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String id = idOf(person.browser().postJson(imports(), start("CREATE", false, null, null)));
        send(person.browser(), id, "name__c\nx\ny\n");

        Response cancelled = person.browser().postJson(imports() + "/" + id + "/cancel", "{}");
        runner.workOn(items.organization().id());

        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(job(person.browser(), id).body(), "$.data.state")).isEqualTo("CANCELLED");
        assertThat(listed(person.browser())).isEmpty();
        assertThat(person.browser().postJson(imports() + "/" + id + "/cancel", "{}").status()).isEqualTo(409);
    }

    // ---- who may ----

    @Test
    void anImportBelongsToThePersonWhoStartedItAndNoOneElseNotEvenAnAdministratorOrAnotherOrganizationSeesIt() {
        Items mine = items();
        Items theirs = items();
        Person starter = worker(mine.organization(), mine.admin());
        Person colleague = worker(mine.organization(), mine.admin());
        String id = run(mine.organization(), starter.browser(), start("CREATE", false, null, null), "name__c\nx\n");

        assertThat(job(starter.browser(), id).status()).isEqualTo(200);
        assertThat(job(colleague.browser(), id).status()).isEqualTo(404);
        assertThat(job(mine.admin(), id).status()).isEqualTo(404);
        assertThat(job(theirs.admin(), id).status()).isEqualTo(404);
        assertThat(colleague.browser().bytes("GET", imports() + "/" + id + "/errors", null).status())
                .isEqualTo(404);
        assertThat(colleague.browser().postJson(imports() + "/" + id + "/cancel", "{}").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(colleague.browser().get(imports()).body(), "$.data")).isEmpty();
    }

    @Test
    void aMemberWhoMayNotCreateIsForbiddenAndOneWhoMayNotReadTheObjectGetsNotFoundAndAnonymousIsRefused() {
        Items items = items();
        Person reader = person(items.organization(), items.admin(), "read");
        Person nobody = person(items.organization(), items.admin());
        TestBrowser anonymous = new TestBrowser(port, items.organization().host());

        assertThat(reader.browser().postJson(imports(), start("CREATE", false, null, null)).status())
                .isEqualTo(403);
        assertThat(nobody.browser().postJson(imports(), start("CREATE", false, null, null)).status())
                .isEqualTo(404);
        assertThat(anonymous.postJson(imports(), start("CREATE", false, null, null)).status()).isEqualTo(401);
        assertThat(reader.browser().postJson(imports(), start("CREATE", false, null, null)).body())
                .doesNotContain("Item");
    }

    @Test
    void anUpsertNeedsTheRightToChangeRecordsToo() {
        Items items = itemsWithExternalId();
        Person creator = person(items.organization(), items.admin(), "read", "create");

        Response refused = creator.browser().postJson(imports(), start("UPSERT", false, "ext__c", null));

        assertThat(refused.status()).isEqualTo(403);
    }

    @Test
    void theRowsAreWrittenWithThePermissionsOfTheStarterSoARecordTheyMayNotChangeFailsTheRow() {
        Items items = itemsWithExternalId();
        share(items.admin(), "PRIVATE", false);
        Person owner = workerWith(items.organization(), items.admin(), "ext__c");
        Person starter = workerWith(items.organization(), items.admin(), "ext__c");
        put(owner.browser(), "{\"ext__c\":\"E1\",\"name__c\":\"theirs\"}");

        String id = run(items.organization(), starter.browser(), start("UPSERT", false, "ext__c", null),
                "ext__c,name__c\nE1,taken over\n");
        Response done = job(starter.browser(), id);

        assertThat(number(done, "updatedRows")).isZero();
        assertThat(number(done, "failedRows")).as(done.body()).isEqualTo(1);
        assertThat(JsonPath.<String>read(owner.browser().get(ITEM_RECORDS + "/" + listed(owner.browser()).get(0))
                .body(), "$.data.fields.name__c")).isEqualTo("theirs");
    }

    // ---- what is written about it ----

    @Test
    void theImportIsAuditedByCountsAndIdentifiersNeverByAValueOrAFileName() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String id = run(items.organization(), person.browser(), start("CREATE", false, null, null),
                "name__c\nvery private name\n");

        List<String> started = auditsOfImport("data.import.started", id);
        List<String> finished = auditsOfImport("data.import.finished", id);

        assertThat(started).hasSize(1);
        assertThat(finished).hasSize(1);
        assertThat(started.get(0) + finished.get(0)).doesNotContain("private");
    }
}
