package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A real approval notice opens the template tab; revoking read access gives a safe explanation. */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateNoticeOpenIT extends TemplateSupport {
    @Test
    void aNoticeOpensItsTemplateAndStopsOpeningItWhenAccessIsRemoved() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person reviewer = plainApprover(setting);
        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        fill(author.browser(), id, firstVersion(author.browser(), id));
        String process = process(setting.admin(), "Review", step("Only", user(reviewer.userId())));
        submitted(author.browser(), id, process);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !tasks(reviewer).isEmpty());
        String notice = tasks(reviewer).getFirst();
        String open = "/api/v1/notifications/" + notice + "/open";
        assertThat(author.browser().postJson(open, "{}").status()).isEqualTo(404);
        Response allowed = reviewer.browser().postJson(open, "{}");
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(allowed.body(), "$.data.href"))
                .isEqualTo("/apps/inspection/s-templates?open=" + id);
        String policy = policyId(setting.admin(), "Approver");
        Response removed = setting.admin().request("DELETE",
                MEMBERS + "/" + reviewer.membership() + "/policies/" + policy, null);
        assertThat(removed.status()).as(removed.body()).isIn(200, 204);
        Response unavailable = reviewer.browser().postJson(open, "{}");
        assertThat(unavailable.status()).as(unavailable.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(unavailable.body(), "$.data.href")).isEqualTo("/notifications");
        assertThat(JsonPath.<String>read(unavailable.body(), "$.data.explanation")).contains("no record you can open");
    }

    private static List<String> tasks(Person person) {
        Response list = person.browser().get("/api/v1/notifications");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[?(@.kind == 'APPROVAL_TASK')].id");
    }
}
