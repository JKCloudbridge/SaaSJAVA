package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The second exit test of Sprint 32 (ADR-0280 to ADR-0282): a connected application over its limit gets 429 with
 * {@code Retry-After} and the headers, while the same member's own token and another application are unaffected; the
 * organization total holds across applications; the numbers in the headers are exact; a bulk call costs its records; a
 * repeated keyed request still counts; and the description document and the internal interface are not counted. (The
 * quota reset at midnight is held by {@code RateLimitsTest} with a clock the test moves.) The limits of this class are
 * 5 a minute for an application or a member, 12 a minute and 1,000 a day for an organization.
 */
@RateLimitedTest
class PublicRateLimitIT extends RateLimitSupport {

    @Test
    void anApplicationOverItsLimitIsRefusedWhileTheMembersOwnTokenAndAnotherApplicationAreNot() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Program greedy = program(organization, admin);
        Program calm = program(organization, admin);
        Caller person = administrator(organization);

        for (int i = 0; i < 5; i++) {
            assertThat(greedy.ask().status()).as("request " + (i + 1)).isEqualTo(200);
        }
        Response refused = greedy.ask();

        assertRefusedWithTheTimeToWait(refused);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("RATE_LIMITED");
        assertThat(person.get(OBJECTS).status()).as("the member's own token has its own counter").isEqualTo(200);
        assertThat(calm.ask().status()).as("another application is unaffected").isEqualTo(200);
        assertThat(greedy.ask().status()).as("it stays refused until its window ends").isEqualTo(429);
    }

    @Test
    void theNumbersInTheHeadersAreExactAndFallWithEveryRequest() {
        Organization organization = organizationWithClients();
        Program program = program(organization, adminOf(organization));

        Response first = program.ask();
        Response second = program.ask();

        assertThat(first.status()).isEqualTo(200);
        assertThat(quota(first, "minute")).isEqualTo(5);
        assertThat(remaining(first, "minute")).isEqualTo(4);
        assertThat(remaining(second, "minute")).isEqualTo(3);
        assertThat(quota(first, "day")).isEqualTo(1000);
        assertThat(remaining(first, "day")).isEqualTo(999);
        assertThat(remaining(second, "day")).isEqualTo(998);
        assertThat(resetsIn(first, "minute")).isBetween(1L, 60L);
        assertThat(resetsIn(first, "day")).isBetween(1L, 86_400L);
    }

    @Test
    void theOrganizationTotalHoldsAcrossApplications() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        List<Program> programs = List.of(program(organization, admin), program(organization, admin),
                program(organization, admin));
        int accepted = 0;
        int refused = 0;

        for (int round = 0; round < 5; round++) {
            for (Program program : programs) {
                if (program.ask().status() == 200) {
                    accepted++;
                } else {
                    refused++;
                }
            }
        }

        assertThat(accepted).as("three applications of five each, one organization of twelve").isEqualTo(12);
        assertThat(refused).isEqualTo(3);
    }

    @Test
    void aBulkCallCostsItsRecordsAndOneThatDoesNotFitIsRefusedWhole() {
        Organization organization = organizationWithClients();
        Program program = program(organization, adminOf(organization));
        String three = "{\"records\":[{\"operation\":\"CREATE\",\"fields\":{}},{\"operation\":\"CREATE\","
                + "\"fields\":{}},{\"operation\":\"CREATE\",\"fields\":{}}]}";
        String nine = "{\"records\":[" + String.join(",", java.util.Collections.nCopies(9,
                "{\"operation\":\"CREATE\",\"fields\":{}}")) + "]}";

        Response bulk = program.caller().post(EMPLOYEES_API + "/records/bulk", three);
        Response tooMany = program.caller().post(EMPLOYEES_API + "/records/bulk", nine);

        assertThat(remaining(bulk, "minute")).as("three records, three requests").isEqualTo(2);
        assertRefusedWithTheTimeToWait(tooMany);
    }

    @Test
    void aRepeatedKeyedRequestStillCountsBecauseItReachedTheServer() {
        Organization organization = organizationWithClients();
        Program program = program(organization, adminOf(organization));
        String key = key();
        String body = createBody("{\"name__c\":\"Once\"}");

        Response first = program.caller().post(EMPLOYEE_RECORDS, body, "Idempotency-Key", key);
        Response repeat = program.caller().post(EMPLOYEE_RECORDS, body, "Idempotency-Key", key);

        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(repeat.header("Idempotent-Replayed")).contains("true");
        assertThat(remaining(first, "minute")).isEqualTo(4);
        assertThat(remaining(repeat, "minute")).as("the repeat is a request too").isEqualTo(3);
    }

    @Test
    void theDescriptionDocumentAndTheInternalInterfaceAreNotCounted() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Program program = program(organization, admin);

        for (int i = 0; i < 20; i++) {
            assertThat(admin.get("/api/v1/webhooks").status()).as("internal request " + i).isEqualTo(200);
            assertThat(anonymous(organization.host()).get("/api/public/v1/openapi").status()).isEqualTo(200);
        }

        Response first = program.ask();
        assertThat(remaining(first, "minute")).as("nothing of the above was counted").isEqualTo(4);
        assertThat(anonymous(organization.host()).get("/api/public/v1/openapi").header("RateLimit")).isEmpty();
    }

    @Test
    void aRefusalDoesNotSayWhichLimitWasFullAndKeepsTheSameWords() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Program application = program(organization, admin);
        for (int i = 0; i < 5; i++) {
            application.ask();
        }
        Response byApplication = application.ask();
        Organization other = organizationWithClients();
        List<Program> programs = List.of(program(other, adminOf(other)), program(other, adminOf(other)),
                program(other, adminOf(other)));
        Response byOrganization = null;
        for (int round = 0; round < 5 && byOrganization == null; round++) {
            for (Program program : programs) {
                Response answer = program.ask();
                if (answer.status() == 429) {
                    byOrganization = answer;
                    break;
                }
            }
        }

        assertThat(byOrganization).isNotNull();
        assertThat(JsonPath.<String>read(byApplication.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(byOrganization.body(), "$.error.message"));
        assertThat(JsonPath.<String>read(byApplication.body(), "$.error.code"))
                .isEqualTo(JsonPath.<String>read(byOrganization.body(), "$.error.code"));
    }

    @Test
    void theUsagePageShowsTheLimitsAndWhatHasBeenUsedAndCostsNothing() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Program program = program(organization, admin);
        program.ask();
        program.ask();

        Response usage = admin.get("/api/v1/usage");
        Response again = admin.get("/api/v1/usage");

        assertThat(usage.status()).as(usage.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(usage.body(), "$.data.enabled")).isTrue();
        assertThat(JsonPath.<Integer>read(usage.body(), "$.data.organization[?(@.key=='minute')].limit[0]"))
                .isEqualTo(12);
        assertThat(JsonPath.<Integer>read(usage.body(), "$.data.organization[?(@.key=='day')].limit[0]"))
                .isEqualTo(1000);
        assertThat(JsonPath.<Integer>read(usage.body(), "$.data.organization[?(@.key=='minute')].used[0]"))
                .isEqualTo(2);
        assertThat(JsonPath.<Integer>read(usage.body(), "$.data.applications[0].limits[0].used")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(again.body(), "$.data.applications[0].limits[0].used"))
                .as("looking costs nothing").isEqualTo(2);
        assertThat(usage.body()).doesNotContain(program.app().secret());
    }
}
