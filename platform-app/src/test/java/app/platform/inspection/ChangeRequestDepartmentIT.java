package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The department kept with a change request (FA6A, ADR-0196): worked out from the department group records and the
 * groups of the person (nested ones included) at the moment they ask, kept as written, nothing (a dash on screen) for a
 * person in no department group, every department for a person in several, and never changed by a later move.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestDepartmentIT extends ChangeRequestSupport {

    private static final String PRODUCTION = "PLANT1 - Plant 1 \u00b7 PRD - Production";

    private static String department(app.platform.testsupport.TestHttp.Response one) {
        try {
            return JsonPath.read(one.body(), "$.data.requesterDepartment");
        } catch (com.jayway.jsonpath.PathNotFoundException none) {
            return null;
        }
    }

    @Test
    void theRequestKeepsTheDepartmentOfTheRequesterAndShowsItToTheOwnerAndTheApprover() {
        World world = world();

        String request = asked(world.reader().browser(), world.template(), "Please check", 1);

        assertThat(department(one(world.reader().browser(), world.template(), request))).isEqualTo(PRODUCTION);
        assertThat(department(one(world.author().browser(), world.template(), request))).isEqualTo(PRODUCTION);
        assertThat(department(one(world.approver().browser(), world.template(), request))).isEqualTo(PRODUCTION);
        assertThat(JsonPath.<java.util.List<String>>read(list(world.author().browser(), world.template()).body(),
                "$.data.requests[*].requesterDepartment")).containsExactly(PRODUCTION);
    }

    @Test
    void aPersonInNoDepartmentGroupHasNone() {
        World world = world();

        String request = asked(world.approver().browser(), world.template(), "From an approver", 1);

        assertThat(department(one(world.approver().browser(), world.template(), request))).isNull();
    }

    @Test
    void aPersonInSeveralDepartmentsHasThemAllAndAGroupInsideAGroupCounts() {
        World world = world();
        String inner = group(world.setting().admin(), "inner-" + System.nanoTime(), world.colleague());
        String outer = group(world.setting().admin(), "outer-" + System.nanoTime());
        assertThat(world.setting().admin().postJson("/api/v1/groups/" + outer + "/members",
                "{\"groupId\":\"" + inner + "\"}").status()).isIn(200, 201, 204);
        department(world.setting().admin(), "PLANT2", "QLT", outer);
        department(world.setting().admin(), "PLANT1", "QLT", group(world.setting().admin(), "q-" + System.nanoTime(),
                world.colleague()));

        String request = asked(world.colleague().browser(), world.template(), "From two", 1);

        String words = department(one(world.colleague().browser(), world.template(), request));
        assertThat(words).contains(PRODUCTION).contains("PLANT1 - Plant 1 \u00b7 QLT - Quality")
                .contains("PLANT2 - Plant 2 \u00b7 QLT - Quality").contains("; ");
    }

    @Test
    void aLaterMoveOfThePersonNeverRewritesWhatTheRequestSaid() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Before the move", 1);
        String elsewhere = group(world.setting().admin(), "else-" + System.nanoTime(), world.reader());
        department(world.setting().admin(), "PLANT2", "QLT", elsewhere);

        String later = asked(world.reader().browser(), world.template(), "After the move", 1);

        assertThat(department(one(world.reader().browser(), world.template(), request))).isEqualTo(PRODUCTION);
        assertThat(department(one(world.reader().browser(), world.template(), later))).contains(PRODUCTION)
                .contains("PLANT2 - Plant 2 \u00b7 QLT - Quality");
        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_change_request where "
                + "requester_department is not null and tenant_id = ?",
                world.setting().organization().id().value())).isEqualTo(2);
    }
}
