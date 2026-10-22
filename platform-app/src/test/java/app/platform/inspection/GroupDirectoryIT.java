package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A field that points at a group (FA4, ADR-0190): the object {@code Group} that the record store answers through the
 * directory of the security module, never by reading its tables. The department setup is the first user: its
 * {@code groupId} accepts a group of this organization and no other, names the groups for the picker, and says the same
 * thing for a group that is removed, one of another organization and one that never existed.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class GroupDirectoryIT extends TemplateSupport {

    private static final String LOOKUPS = "/api/v1/objects/" + DEPARTMENT_OBJECT + "/lookups/groupId";

    private static Response labels(TestBrowser browser, String... ids) {
        return browser.postJson(LOOKUPS + "/labels", "{\"ids\":[" + String.join(",",
                java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").toList()) + "]}");
    }

    private static Response options(TestBrowser browser, String text) {
        return browser.postJson(LOOKUPS + "/options", "{\"text\":\"" + text + "\"}");
    }

    private static Response mapping(TestBrowser browser, String groupId) {
        return browser.postJson(DEPARTMENTS, "{\"fields\":{\"name\":\"x\",\"location\":\"PLANT1\","
                + "\"department\":\"PRD\",\"groupId\":\"" + groupId + "\"}}");
    }

    @Test
    void aGroupOfThisOrganizationIsNamedOfferedAndAccepted() {
        Setting setting = setting();
        String quality = group(setting.admin(), "Quality team " + System.nanoTime());
        group(setting.admin(), "Stores team " + System.nanoTime());

        Map<String, String> named = JsonPath.read(labels(setting.admin(), quality).body(), "$.data.labels");
        List<String> offered = JsonPath.read(options(setting.admin(), "quality").body(), "$.data[*].label");
        Response saved = mapping(setting.admin(), quality);

        assertThat(named).containsOnlyKeys(quality);
        assertThat(named.get(quality)).startsWith("Quality team");
        assertThat(offered).hasSize(1).first().asString().startsWith("Quality team");
        assertThat(saved.status()).as(saved.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(saved.body(), "$.data.fields.groupId")).isEqualTo(quality);
    }

    @Test
    void aGroupOfAnotherOrganizationARemovedOneAndOneThatNeverWasAreRefusedAndNamedAlike() {
        Setting mine = setting();
        Setting theirs = setting();
        String foreign = group(theirs.admin(), "Their team " + System.nanoTime());
        String removed = group(mine.admin(), "Gone team " + System.nanoTime());
        assertThat(mine.admin().request("DELETE", GROUPS + "/" + removed, null).status()).isEqualTo(204);
        String never = UUID.randomUUID().toString();

        Map<String, String> named = JsonPath.read(labels(mine.admin(), foreign, removed, never).body(),
                "$.data.labels");
        Response one = mapping(mine.admin(), foreign);
        Response two = mapping(mine.admin(), removed);
        Response three = mapping(mine.admin(), never);

        assertThat(named).isEmpty();
        assertThat(List.of(one.status(), two.status(), three.status())).containsOnly(400);
        assertThat(JsonPath.<String>read(one.body(), "$.error.fields.groupId[0]"))
                .isEqualTo(JsonPath.<String>read(two.body(), "$.error.fields.groupId[0]"))
                .isEqualTo(JsonPath.<String>read(three.body(), "$.error.fields.groupId[0]"));
        assertThat(JsonPath.<List<String>>read(options(mine.admin(), "Their").body(), "$.data[*].label")).isEmpty();
    }

    @Test
    void theNamesOfGroupsAreOfferedOnlyToThoseWhoMayReadTheDepartmentSetup() {
        Setting setting = setting();
        Person plain = outsider(setting);
        String group = group(setting.admin(), "Hidden team " + System.nanoTime());

        Response asked = options(plain.browser(), "Hidden");

        assertThat(asked.status()).as("a member with no right on the object is told no more than for an unknown one")
                .isEqualTo(404);
        assertThat(labels(plain.browser(), group).status()).isEqualTo(404);
    }

    @Test
    void theStandardGroupObjectIsKeptByTheSecurityPartAndNeverStoresRecordsOfItsOwn() {
        Setting setting = setting();

        Response created = setting.admin().postJson("/api/v1/objects/Group/records",
                "{\"fields\":{\"name\":\"made through the record store\"}}");

        assertThat(created.status()).as("its records belong to the groups screens, not to the record store")
                .isGreaterThanOrEqualTo(400);
        assertThat(scalar(setting.organization(), "select count(*) from object_record where object_api_name = 'Group'"))
                .isZero();
    }
}
