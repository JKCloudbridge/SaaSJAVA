package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The bound of the home page (FA9, ADR-0203): with the page allowed to read a single record of each kind, a number that
 * could have been larger says so ("at least"), and the numbers it does give are those of what it read, never invented.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "platform.inspection.home.max-scanned=1"})
class QualityHomeBoundIT extends FillSupport {

    @Test
    void aNumberThatReachedTheBoundSaysAtLeastAndStopsThere() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        templateWithSheet(world.author().browser(), "SOP", "QLT");
        for (int i = 0; i < 2; i++) {
            String fill = started(world.filler().browser(), world.template());
            saved(world.filler().browser(), fill, complete());
            assertThat(world.filler().browser().postJson(FILL_INS + "/" + fill + "/submit", "{}").status())
                    .isEqualTo(200);
        }

        Response home = world.approver().browser().postJson("/api/v1/quality-home/summary", "{}");

        assertThat(home.status()).as(home.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(home.body(), "$.data.tiles.activeTemplates.atLeast")).isTrue();
        assertThat(JsonPath.<Boolean>read(home.body(), "$.data.tiles.fillsSubmitted.atLeast")).isTrue();
        assertThat(JsonPath.<Number>read(home.body(), "$.data.tiles.fillsSubmitted.value").intValue()).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(home.body(), "$.data.charts.fillsPerDay.atLeast")).isTrue();
    }
}
