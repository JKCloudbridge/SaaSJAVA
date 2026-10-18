package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.BackgroundWriteException.Reason;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Chain;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform's own writes (ADR-0139) held against a person's: the same write made by an administrator through the API
 * and by the platform through {@link BackgroundWrites} ends in the same stored values, the formulas included, and every
 * refusal of the pipeline has a stable reason that names no value. The platform skips only what is about who writes: it
 * changes no owner, share or version on its own account, announces its writes with the chain, and is audited with the
 * workflow and no person.
 */
@PlatformIntegrationTest
class BackgroundWritesIT extends RecordsSupport {

    @Autowired
    private BackgroundWrites writes;

    @Autowired
    private app.platform.data.internal.BackfillDriver driver;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    private static final UUID EXECUTION = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        Response rule = admin.postJson(METADATA_OBJECTS + "/Employee__c/validation-rules",
                "{\"name\":\"NoBanned\",\"label\":\"NoBanned\",\"expression\":\"name__c = 'banned'\","
                        + "\"errorMessage\":\"That name is not allowed.\"}");
        assertThat(rule.status()).as(rule.body()).isEqualTo(201);
        return new Setup(organization, admin);
    }

    private <T> T asPlatform(Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static Source source() {
        return new Source("Test__c", EXECUTION, new Chain(UUID.randomUUID(), 1, List.of(WORKFLOW)));
    }

    private static Assignment literal(String field, String text) {
        return new Assignment(field, new Value.Literal(text));
    }

    private static Assignment copied(String field, Object stored) {
        return new Assignment(field, new Value.Copied(stored));
    }

    private DataRecord updateAsPlatform(Setup setup, String id, Assignment... assignments) {
        return asPlatform(setup.organization(),
                () -> writes.update(source(), "Employee__c", UUID.fromString(id), List.of(assignments)));
    }

    private BackgroundWriteException refusedUpdate(Setup setup, String id, Assignment... assignments) {
        return org.junit.jupiter.api.Assertions.assertThrows(BackgroundWriteException.class,
                () -> updateAsPlatform(setup, id, assignments));
    }

    /** A person's change of the record as it is now (the version is read, as a screen does). */
    private static Response patchNow(TestBrowser admin, String id, String fieldsJson) {
        long version = JsonPath.<Number>read(admin.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
        return patch(admin, id, version, fieldsJson);
    }

    /** The fields of a record without its auto-number: two records never hold the same one. */
    private static Map<String, Object> fieldsOf(TestBrowser admin, String id) {
        Map<String, Object> fields = new java.util.HashMap<>(
                JsonPath.<Map<String, Object>>read(admin.get(RECORDS + "/" + id).body(), "$.data.fields"));
        fields.remove("sequence");
        return fields;
    }

    private static void constrain(TestBrowser admin, String field, boolean required, boolean unique) {
        long version = JsonPath.<List<Number>>read(admin.get(METADATA_OBJECTS + "/Employee__c").body(),
                "$.data.fields[?(@.apiName=='" + field + "')].version").get(0).longValue();
        Response changed = admin.request("PUT", METADATA_OBJECTS + "/Employee__c/fields/" + field,
                "{\"label\":\"" + field + "\",\"description\":\"\",\"required\":" + required + ",\"unique\":"
                        + unique + ",\"version\":" + version + "}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    }

    // ---- the same write, the same stored values ----

    @Test
    void theSameUpdateByTheAdministratorAndByThePlatformStoresTheSameValues() {
        Setup setup = setup();
        String byPerson = newRecord(setup.admin(), "Ann");
        String byPlatform = newRecord(setup.admin(), "Ann");

        Response person = patchNow(setup.admin(), byPerson,
                "{\"name__c\":\"Bea\",\"salary__c\":100.5,\"active__c\":true,\"tags__c\":[\"a\",\"b\"],"
                        + "\"notes__c\":\"note\"}");
        updateAsPlatform(setup, byPlatform, literal("name__c", "Bea"), literal("salary__c", "100.5"),
                literal("active__c", "true"), literal("tags__c", "a;b"), literal("notes__c", "note"));

        assertThat(person.status()).as(person.body()).isEqualTo(200);
        Map<String, Object> left = fieldsOf(setup.admin(), byPerson);
        Map<String, Object> right = fieldsOf(setup.admin(), byPlatform);
        assertThat(right).isEqualTo(left);
        assertThat(right.get("doubled__c")).as("the formula was worked out as for a person").isNotNull();
    }

    @Test
    void theSameCreateByTheAdministratorAndByThePlatformStoresTheSameValuesAndTheSameDefaults() {
        Setup setup = setup();

        Response person = create(setup.admin(), "{\"name__c\":\"Cy\",\"department__c\":\"Ops\",\"salary__c\":10}");
        DataRecord made = asPlatform(setup.organization(), () -> writes.create(source(), "Employee__c",
                setup.organization().admin().person().user().id(), List.of(literal("name__c", "Cy"),
                        literal("department__c", "Ops"), literal("salary__c", "10"))));

        Map<String, Object> left = fieldsOf(setup.admin(), idOf(person));
        Map<String, Object> right = fieldsOf(setup.admin(), made.id().toString());
        assertThat(right).isEqualTo(left);
    }

    @Test
    void aCopyIsStoredAsTheValueTheOtherRecordHolds() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Dee");

        updateAsPlatform(setup, record, copied("department__c", "Moved"), copied("notes__c", null));

        assertThat(fieldsOf(setup.admin(), record)).containsEntry("department__c", "Moved")
                .doesNotContainKey("notes__c");
    }

    // ---- the same refusal, with a reason and no value ----

    @Test
    void everyKindOfRefusalOfThePipelineIsAStableReasonThatNamesNoValue() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Eve");
        constrain(setup.admin(), "department__c", false, true);

        record Case(String label, String adminFields, Assignment platform, Reason reason) {
        }
        List<Case> cases = List.of(
                new Case("a value of the wrong type", "{\"salary__c\":\"SECRET-ABC\"}",
                        literal("salary__c", "SECRET-ABC"), Reason.INVALID_VALUE),
                new Case("a rule that rejects", "{\"name__c\":\"banned\"}", literal("name__c", "banned"),
                        Reason.REJECTED_BY_RULE),
                new Case("a field that is not there", "{\"nope__c\":\"x\"}", literal("nope__c", "x"),
                        Reason.FIELD_NOT_WRITABLE),
                new Case("a field the platform works out", "{\"doubled__c\":5}", literal("doubled__c", "5"),
                        Reason.FIELD_NOT_WRITABLE));

        for (Case one : cases) {
            Response person = patchNow(setup.admin(), record, one.adminFields());
            BackgroundWriteException platform = refusedUpdate(setup, record, one.platform());

            assertThat(person.status()).as(one.label()).isEqualTo(400);
            assertThat(platform.reason()).as(one.label()).isEqualTo(one.reason());
            assertThat(platform.getMessage()).as(one.label()).doesNotContain("SECRET").doesNotContain("banned");
            assertThat(platform.getStackTrace()).isEmpty();
        }
    }

    @Test
    void aValueAlreadyInUseIsAUniqueClashForThePlatformAsItIsForAPerson() {
        Setup setup = setup();
        constrain(setup.admin(), "department__c", false, true);
        // The database enforces uniqueness only once the background work has filled the copy and set the constraint.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).until(() -> {
            driver.workOn(setup.organization().id());
            return JsonPath.<List<String>>read(setup.admin().get(EMPLOYEES + "/index-status").body(),
                    "$.data.uniqueness[*].state").stream().allMatch("ENFORCED"::equals);
        });
        create(setup.admin(), "{\"name__c\":\"Fay\",\"department__c\":\"Only\"}");
        String second = idOf(create(setup.admin(), "{\"name__c\":\"Gus\",\"department__c\":\"Else\"}"));

        Response person = patchNow(setup.admin(), second, "{\"department__c\":\"Only\"}");

        assertThat(person.status()).isEqualTo(400);
        assertThat(refusedUpdate(setup, second, literal("department__c", "Only")).reason())
                .isEqualTo(Reason.UNIQUE_CLASH);
    }

    @Test
    void aRequiredFieldMissingIsARefusalForTheCreateOfThePlatformAsForAPerson() {
        Setup setup = setup();
        constrain(setup.admin(), "name__c", true, false);

        Response person = create(setup.admin(), "{\"department__c\":\"Ops\"}");
        BackgroundWriteException platform = org.junit.jupiter.api.Assertions.assertThrows(
                BackgroundWriteException.class, () -> asPlatform(setup.organization(), () -> writes.create(source(),
                        "Employee__c", null, List.of(literal("department__c", "Ops")))));

        assertThat(person.status()).isEqualTo(400);
        assertThat(platform.reason()).isEqualTo(Reason.REQUIRED_MISSING);
    }

    @Test
    void theRecordsThatAreGoneOrInTheBinOrNotKeptHereAreRefusedWithTheirOwnReasons() {
        Setup setup = setup();
        String binned = newRecord(setup.admin(), "Hal");
        setup.admin().request("DELETE", RECORDS + "/" + binned, null);

        assertThat(refusedUpdate(setup, binned, literal("name__c", "x")).reason()).isEqualTo(Reason.RECORD_IN_BIN);
        assertThat(refusedUpdate(setup, UUID.randomUUID().toString(), literal("name__c", "x")).reason())
                .isEqualTo(Reason.RECORD_GONE);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(BackgroundWriteException.class,
                () -> asPlatform(setup.organization(), () -> writes.update(source(), "User", UUID.randomUUID(),
                        List.of(literal("firstName", "x"))))).reason()).isEqualTo(Reason.OBJECT_UNAVAILABLE);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(BackgroundWriteException.class,
                () -> asPlatform(setup.organization(), () -> writes.create(source(), "Nothing__c", null, List.of())))
                .reason()).isEqualTo(Reason.OBJECT_UNAVAILABLE);
    }

    // ---- what the platform does not do on its own account ----

    @Test
    void anUpdateChangesNoOwnerAndMakesNoShareAndBumpsTheVersionOnce() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Ivy");
        String owner = JsonPath.read(setup.admin().get(RECORDS + "/" + record).body(), "$.data.ownerId");
        long version = JsonPath.<Number>read(setup.admin().get(RECORDS + "/" + record).body(), "$.data.version")
                .longValue();

        updateAsPlatform(setup, record, literal("name__c", "Ivy2"));

        String after = setup.admin().get(RECORDS + "/" + record).body();
        assertThat(JsonPath.<String>read(after, "$.data.ownerId")).isEqualTo(owner);
        assertThat(JsonPath.<Number>read(after, "$.data.version").longValue()).isEqualTo(version + 1);
        assertThat(owner("select count(*) from record_share where record_id = cast(? as uuid)", record))
                .containsExactly("0");
    }

    @Test
    void aCreatedRecordHasTheOwnerItWasGivenAndThePlatformAsItsMaker() {
        Setup setup = setup();
        UUID owner = setup.organization().admin().person().user().id();

        DataRecord made = asPlatform(setup.organization(), () -> writes.create(source(), "Employee__c", owner,
                List.of(literal("name__c", "Jo"))));

        assertThat(made.ownerId()).isEqualTo(owner);
        assertThat(owner("select created_by::text from object_record where id = cast(? as uuid)",
                made.id().toString())).containsExactly("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void anUpdateThatWouldChangeNothingIsNotAChange() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Kim");
        long version = JsonPath.<Number>read(setup.admin().get(RECORDS + "/" + record).body(), "$.data.version")
                .longValue();

        DataRecord same = updateAsPlatform(setup, record, literal("name__c", "Kim"));

        assertThat(same.version()).isEqualTo(version);
        assertThat(eventsOf("record.updated", record)).isEmpty();
        assertThat(auditsOf("data.record.updated", record)).isEmpty();
    }

    // ---- what the platform leaves behind ----

    @Test
    void theEventCarriesTheChainAndTheAuditRecordTheWorkflowAndNoPersonAndNeverAValue() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Lee");

        updateAsPlatform(setup, record, literal("name__c", "SECRET-NAME"));

        List<String> events = eventsOf("record.updated", record);
        assertThat(events).hasSize(1);
        assertThat(JsonPath.<Integer>read(events.get(0), "$.chain.depth")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(events.get(0), "$.chain.workflows")).containsExactly(
                WORKFLOW.toString());
        assertThat(JsonPath.<List<String>>read(events.get(0), "$.fields")).containsExactly("name__c");
        assertThat(events.get(0)).doesNotContain("SECRET-NAME");
        assertThat(auditsOf("data.record.updated", record)).singleElement().satisfies(audit -> {
            assertThat(audit).contains("Test__c").contains(EXECUTION.toString()).contains("platform");
            assertThat(audit).doesNotContain("SECRET-NAME");
        });
        assertThat(owner("select coalesce(actor_user_id::text, 'none') || '|' || coalesce(attributes ->> 'actor', '') "
                + "from audit_record where event_type = 'data.record.updated' and record_id = ?", record))
                .containsExactly("none|platform");
    }

    @Test
    void aPersonsWriteStillCarriesNoChain() {
        Setup setup = setup();
        String record = newRecord(setup.admin(), "Max");

        patchNow(setup.admin(), record, "{\"name__c\":\"Max2\"}");

        assertThat(eventsOf("record.updated", record)).singleElement().satisfies(event ->
                assertThat(event).doesNotContain("chain"));
    }

    @Test
    void theInterfaceIsReachedOnlyInsideATenantContext() {
        assertThatThrownBy(() -> writes.create(source(), "Employee__c", null, List.of()))
                .isInstanceOf(RuntimeException.class);
    }
}
