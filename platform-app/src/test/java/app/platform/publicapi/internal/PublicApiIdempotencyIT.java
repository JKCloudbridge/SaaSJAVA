package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * A caller that loses an answer must not make a second record (Sprint 30, ADR-0246): the same key with the same request
 * makes one record whatever the number of attempts, on one instance or two, in parallel or one after the other; the
 * same
 * key with another request is told so; a request that was refused leaves nothing behind; and what is remembered holds
 * identifiers and no value.
 */
@PlatformIntegrationTest
class PublicApiIdempotencyIT extends ClientSupport {

    private static final String BODY = createBody("{\"name__c\":\"Once\",\"department__c\":\"Sales\"}");

    private Caller program() {
        Organization organization = organization();
        adminWithEmployee(organization);
        return administrator(organization);
    }

    private static long count(Caller program, String name) {
        return JsonPath.<List<String>>read(program.post(EMPLOYEE_RECORDS + "/query",
                "{\"filter\":{\"op\":\"EQ\",\"field\":\"name__c\",\"values\":[\"" + name + "\"]}}").body(),
                "$.data[*].id").size();
    }

    @Test
    void theSameKeyAndTheSameRequestMakeOneRecordAndTheRepeatSaysSo() {
        Caller program = program();
        String key = key();

        Response first = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        Response again = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isEmpty();
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        assertThat(again.header("Idempotent-Replayed")).contains("true");
        assertThat(id(again)).isEqualTo(id(first));
        assertThat(etag(again)).isEqualTo(etag(first));
        assertThat(count(program, "Once")).isEqualTo(1);
    }

    @Test
    void aRepeatShowsTheRecordAsItIsNowBecauseTheMemoryHoldsNoValue() {
        Caller program = program();
        String key = key();
        Response first = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        program.patch(EMPLOYEE_RECORDS + "/" + id(first), "{\"fields\":{\"department__c\":\"Changed\"}}",
                "If-Match", etag(first));

        Response again = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(again.header("Idempotent-Replayed")).contains("true");
        assertThat(JsonPath.<String>read(again.body(), "$.data.fields.department__c")).isEqualTo("Changed");
        assertThat(JsonPath.<Number>read(again.body(), "$.data.version").longValue()).isEqualTo(1L);
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflictAndMakesNothing() {
        Caller program = program();
        String key = key();
        program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        Response other = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Other\"}"), "Idempotency-Key", key);

        assertThat(other.status()).isEqualTo(409);
        assertThat(code(other)).isEqualTo("CONFLICT");
        assertThat(JsonPath.<String>read(other.body(), "$.error.message"))
                .isEqualTo("This Idempotency-Key was already used with a different request.");
        assertThat(count(program, "Other")).isZero();
        assertThat(count(program, "Once")).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherKindOfRequestOrAnotherObjectIsAConflict() {
        Caller program = program();
        String key = key();
        program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        Response bulk = program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":["
                + "{\"fields\":{\"name__c\":\"Once\",\"department__c\":\"Sales\"}}]}", "Idempotency-Key", key);

        assertThat(bulk.status()).isEqualTo(409);
        assertThat(code(bulk)).isEqualTo("CONFLICT");
        assertThat(count(program, "Once")).isEqualTo(1);
    }

    @Test
    void aKeyMeansOneThingForOnePersonAndAnotherPersonMayUseTheSameText() {
        Organization organization = organization();
        var admin = adminWithEmployee(organization);
        Caller first = administrator(organization);
        Caller second = memberWith(organization, admin, "read", "create");
        String key = key();

        Response one = first.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        Response two = second.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(one.status()).isEqualTo(201);
        assertThat(two.status()).isEqualTo(201);
        assertThat(id(two)).isNotEqualTo(id(one));
        assertThat(two.header("Idempotent-Replayed")).isEmpty();
    }

    @Test
    void aRequestThatWasRefusedLeavesNothingBehindSoTheSameKeyIsAFreshAttempt() {
        Caller program = program();
        String key = key();
        String refused = createBody("{\"name__c\":\"Never\",\"salary__c\":\"text\"}");

        Response first = program.post(EMPLOYEE_RECORDS, refused, "Idempotency-Key", key);
        Response second = program.post(EMPLOYEE_RECORDS, refused, "Idempotency-Key", key);

        assertThat(first.status()).isEqualTo(400);
        assertThat(second.status()).as("refused again for its own reason, not as a replay").isEqualTo(400);
        assertThat(second.header("Idempotent-Replayed")).isEmpty();
        assertThat(owner("select count(*) from idempotency_record where object_api_name = 'Employee__c' and "
                + "tenant_id = ?::uuid", tenantOf(program))).containsExactly("0");
    }

    @Test
    void aKeyOfTheWrongShapeIsRefusedAtItsHeaderWithoutRepeatingIt() {
        Caller program = program();

        Response answer = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", "short");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.error.fields['Idempotency-Key']")).isNotEmpty();
        assertThat(answer.body()).doesNotContain("\"short\"");
    }

    @Test
    void manyAttemptsAtOnceWithOneKeyMakeOneRecord() throws ExecutionException, InterruptedException {
        Caller program = program();
        String key = key();
        List<CompletableFuture<Response>> attempts = new ArrayList<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            attempts.add(CompletableFuture.supplyAsync(() -> program.post(EMPLOYEE_RECORDS, BODY,
                    "Idempotency-Key", key)));
        }

        List<String> ids = new ArrayList<>();
        int replays = 0;
        for (CompletableFuture<Response> attempt : attempts) {
            Response answer = attempt.get();
            assertThat(answer.status()).as(answer.body()).isEqualTo(201);
            ids.add(id(answer));
            replays += answer.header("Idempotent-Replayed").isPresent() ? 1 : 0;
        }

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(replays).as("one made it, seven repeated it").isEqualTo(7);
        assertThat(count(program, "Once")).isEqualTo(1);
    }

    @Test
    void twoInstancesSharingOneDatabaseMakeOneRecordForOneKey() throws ExecutionException, InterruptedException {
        Caller program = program();
        String key = key();
        try (TestApplication other = TestApplication.start()) {
            Caller onOther = program.onInstance(other.port());
            List<CompletableFuture<Response>> attempts = new ArrayList<>();
            for (int attempt = 0; attempt < 6; attempt++) {
                Caller via = attempt % 2 == 0 ? program : onOther;
                attempts.add(CompletableFuture.supplyAsync(() -> via.post(EMPLOYEE_RECORDS, BODY,
                        "Idempotency-Key", key)));
            }

            List<String> ids = new ArrayList<>();
            for (CompletableFuture<Response> attempt : attempts) {
                Response answer = attempt.get();
                assertThat(answer.status()).as(answer.body()).isEqualTo(201);
                ids.add(id(answer));
            }

            assertThat(ids).containsOnly(ids.get(0));
            assertThat(count(program, "Once")).isEqualTo(1);
        }
    }

    // ---- connected applications (Sprint 31, ADR-0273) ----

    @Test
    void twoApplicationsActingAsOneMemberDoNotShareAKeySpace() {
        Organization organization = organizationWithClients();
        var admin = adminOf(organization);
        Caller one = clientCaller(organization, register(admin, ALL_SCOPES));
        Caller two = clientCaller(organization, register(admin, ALL_SCOPES));
        String key = key();

        Response first = one.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        Response second = two.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        Response repeatOfFirst = one.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(id(second)).as("a second record, because it is another application's key").isNotEqualTo(id(first));
        assertThat(second.header("Idempotent-Replayed")).isEmpty();
        assertThat(repeatOfFirst.header("Idempotent-Replayed")).contains("true");
        assertThat(id(repeatOfFirst)).isEqualTo(id(first));
        assertThat(count(one, "Once")).isEqualTo(2);
    }

    @Test
    void anApplicationAndItsMembersOwnTokenDoNotShareAKeySpaceEither() {
        Organization organization = organizationWithClients();
        var admin = adminOf(organization);
        Caller application = clientCaller(organization, register(admin, ALL_SCOPES));
        Caller person = administrator(organization);
        String key = key();

        Response byApplication = application.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        Response byPerson = person.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(byApplication.status()).isEqualTo(201);
        assertThat(byPerson.status()).isEqualTo(201);
        assertThat(id(byPerson)).isNotEqualTo(id(byApplication));
        assertThat(byPerson.header("Idempotent-Replayed")).isEmpty();
    }

    @Test
    void theKeySpaceOfAnApplicationIsStableAcrossItsTokensAndItsInstances() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        String key = key();
        Caller before = clientCaller(organization, app);
        Response first = before.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        try (TestApplication other = TestApplication.start()) {
            Caller newToken = clientCaller(organization, app).onInstance(other.port());

            Response again = newToken.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

            assertThat(again.status()).as(again.body()).isEqualTo(201);
            assertThat(again.header("Idempotent-Replayed")).as("the same application with a new token on another "
                    + "instance repeats").contains("true");
            assertThat(id(again)).isEqualTo(id(first));
        }
    }

    @Test
    void whatIsRememberedForAnApplicationHoldsNoSecretAndNoTokenAndNoClientIdentifier() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        String key = key();
        program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        List<String> rows = owner("select key_hash || '|' || request_hash || '|' || caller_id::text || '|' || "
                + "outcome::text from idempotency_record where tenant_id = ?::uuid", organization.id().value()
                        .toString());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).doesNotContain(app.secret()).doesNotContain(program.token())
                .doesNotContain(app.clientId()).doesNotContain(key);
        assertThat(rows.get(0)).as("the caller is neither the member nor the registration")
                .doesNotContain(organization.admin().membership().toString()).doesNotContain(app.id());
    }

    // ---- bulk ----

    @Test
    void aBulkCallWithTheSameKeyIsCarriedOutOnceAndRepeatedFromTheMemory() {
        Caller program = program();
        String key = key();
        String call = "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{\"name__c\":\"Bulk once\"}},"
                + "{\"fields\":{\"salary__c\":\"x\"}},{\"fields\":{\"name__c\":\"Bulk once\"}}]}";

        Response first = program.post(EMPLOYEE_RECORDS + "/bulk", call, "Idempotency-Key", key);
        Response again = program.post(EMPLOYEE_RECORDS + "/bulk", call, "Idempotency-Key", key);

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(again.header("Idempotent-Replayed")).contains("true");
        assertThat(JsonPath.<List<String>>read(again.body(), "$.data.results[*].status"))
                .containsExactly("CREATED", "FAILED", "CREATED");
        assertThat(JsonPath.<List<String>>read(again.body(), "$.data.results[*].id"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(first.body(), "$.data.results[*].id"));
        assertThat(JsonPath.<String>read(again.body(), "$.data.results[1].error.code"))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<String>read(again.body(), "$.data.results[1].error.message"))
                .isEqualTo("This record was refused when the request was first made.");
        assertThat(count(program, "Bulk once")).isEqualTo(2);
    }

    @Test
    void anAllOrNoneCallThatWasUndoneLeavesNoMemorySoItCanBeSentAgain() {
        Caller program = program();
        String key = key();
        String call = "{\"operation\":\"CREATE\",\"allOrNone\":true,\"records\":[{\"fields\":{\"name__c\":\"Undone\"}},"
                + "{\"fields\":{\"salary__c\":\"x\"}}]}";

        Response first = program.post(EMPLOYEE_RECORDS + "/bulk", call, "Idempotency-Key", key);
        Response second = program.post(EMPLOYEE_RECORDS + "/bulk", call, "Idempotency-Key", key);

        assertThat(JsonPath.<Boolean>read(first.body(), "$.data.committed")).isFalse();
        assertThat(second.header("Idempotent-Replayed")).isEmpty();
        assertThat(JsonPath.<Boolean>read(second.body(), "$.data.committed")).isFalse();
        assertThat(count(program, "Undone")).isZero();
        assertThat(owner("select count(*) from idempotency_record where tenant_id = ?::uuid", tenantOf(program)))
                .containsExactly("0");
    }

    @Test
    void whatIsRememberedIsIdentifiersAndNeverAValueAKeyOrARequest() {
        Caller program = program();
        String key = key();
        String secret = "secret-" + UUID.randomUUID();
        Response created = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + secret + "\"}"),
                "Idempotency-Key", key);
        program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{\"name__c\":\""
                + secret + "\"}}]}", "Idempotency-Key", key());

        List<String> rows = owner("select key_hash || '|' || request_hash || '|' || operation || '|' || "
                + "object_api_name || '|' || outcome::text from idempotency_record where tenant_id = ?::uuid",
                tenantOf(program));

        assertThat(rows).hasSize(2);
        for (String row : rows) {
            assertThat(row).doesNotContain(secret).doesNotContain(key).doesNotContain("Idempotency");
        }
        assertThat(String.join("", rows)).contains(id(created));
    }

    private String tenantOf(Caller program) {
        return owner("select id::text from tenant where slug = ?", program.host().split("\\.")[0]).get(0);
    }

    @Test
    void anOutcomeThatHasExpiredIsForgottenAndTheKeyIsFreeAgain() throws InterruptedException {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        String key = key();
        UUID membership = organization.admin().membership();
        // An outcome that expires in a moment, as a short retention would leave it (a row cannot be aged by hand).
        ownerRuns("insert into idempotency_record (tenant_id, caller_id, key_hash, request_hash, operation, "
                + "object_api_name, outcome, expires_at, created_by, updated_by) values ('"
                + organization.id().value() + "', '" + membership + "', '" + IdempotencyKeys.keyHash(key) + "', '"
                + "0".repeat(64) + "', 'CREATE_RECORD', 'Employee__c', '{\"recordId\":\""
                + UUID.randomUUID() + "\",\"version\":0}'::jsonb, now() + interval '1 second', '" + membership
                + "', '" + membership + "')");
        Response whileGood = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);
        assertThat(whileGood.status()).as("another request under a good key").isEqualTo(409);

        Thread.sleep(1500);
        Response afterwards = program.post(EMPLOYEE_RECORDS, BODY, "Idempotency-Key", key);

        assertThat(afterwards.status()).as(afterwards.body()).isEqualTo(201);
        assertThat(afterwards.header("Idempotent-Replayed")).isEmpty();
    }
}
