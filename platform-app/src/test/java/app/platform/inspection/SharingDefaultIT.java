package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The default the platform fixes for the records of a template (FA4, ADR-0189 section 1): private, with the role
 * hierarchy adding nothing, whatever an administrator or a stray row says. A member who may only read templates sees
 * none that were not published to them, a manager above the author in the hierarchy sees none of their drafts, the
 * sharing setup shows the object as fixed and refuses a change, and other objects keep the default an administrator
 * chooses. */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class SharingDefaultIT extends TemplateSupport {

    private static final String SHARING = "/api/v1/sharing/objects/";

    @Test
    void theSharingSetupShowsTheTemplateObjectAsFixedPrivateAndOtherObjectsAsTheAdministratorChoosesThem() {
        Setting setting = setting();

        Response fixed = setting.admin().get(SHARING + OBJECT);
        Response open = setting.admin().get(SHARING + "Account");

        assertThat(fixed.status()).as(fixed.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(fixed.body(), "$.data.fixed")).isTrue();
        assertThat(JsonPath.<String>read(fixed.body(), "$.data.defaultAccess")).isEqualTo("PRIVATE");
        assertThat(JsonPath.<Boolean>read(fixed.body(), "$.data.grantViaHierarchy")).isFalse();
        assertThat(JsonPath.<Boolean>read(open.body(), "$.data.fixed")).isFalse();
        assertThat(JsonPath.<String>read(open.body(), "$.data.defaultAccess")).isEqualTo("PUBLIC_READ_WRITE");
        List<Boolean> all = JsonPath.read(setting.admin().get("/api/v1/sharing/objects").body(),
                "$.data[?(@.object=='" + OBJECT + "')].fixed");
        assertThat(all).containsExactly(true);
    }

    @Test
    void anAdministratorCannotChangeItAndNothingIsStored() {
        Setting setting = setting();

        Response attempt = setting.admin().request("PUT", SHARING + OBJECT,
                "{\"defaultAccess\":\"PUBLIC_READ_WRITE\",\"grantViaHierarchy\":true}");

        assertThat(attempt.status()).isEqualTo(409);
        assertThat(code(attempt)).isEqualTo("CONFLICT");
        assertThat(message(attempt)).startsWith("The platform fixes who reaches the records of this object");
        assertThat(scalar(setting.organization(), "select count(*) from object_sharing_setting where object_key = ?",
                OBJECT)).isZero();
        Response other = setting.admin().request("PUT", SHARING + "Account",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false}");
        assertThat(other.status()).as("another object is still the administrator's to set").isEqualTo(200);
    }

    @Test
    void aRowThatGotInBySomeOtherWayChangesNothing() throws SQLException {
        Setting setting = setting();
        Person author = author(setting);
        Person reader = reader(setting);
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        app.platform.testsupport.tenancy.TenantFixtures.asTenant(setting.organization().id(), connection ->
                app.platform.testsupport.tenancy.TenantFixtures.update(connection,
                        "insert into object_sharing_setting (tenant_id, object_key, default_access, "
                                + "grant_via_hierarchy, created_by, updated_by) values (?, ?, 'PUBLIC_READ_WRITE', "
                                + "true, ?, ?)", setting.organization().id().value(), OBJECT,
                        app.platform.sharedkernel.ActorId.SYSTEM.value(),
                        app.platform.sharedkernel.ActorId.SYSTEM.value()));

        assertThat(listed(reader.browser())).as("the stray row is not even read").doesNotContain(template);
        assertThat(version(reader.browser(), template, 1).status()).isEqualTo(404);
        assertThat(JsonPath.<Boolean>read(setting.admin().get(SHARING + OBJECT).body(), "$.data.fixed")).isTrue();
        assertThat(JsonPath.<String>read(setting.admin().get(SHARING + OBJECT).body(), "$.data.defaultAccess"))
                .isEqualTo("PRIVATE");
    }

    @Test
    void aMemberWhoMayOnlyReadSeesNothingUnpublishedAndAManagerAboveTheAuthorSeesNoDraftOfTheirs() {
        Setting setting = setting();
        Person author = author(setting);
        Person manager = reader(setting);
        Person colleague = reader(setting);
        String parent = role(setting.admin(), "manager-" + System.nanoTime(), null);
        String child = role(setting.admin(), "author-" + System.nanoTime(), parent);
        putInRole(setting.admin(), manager, parent);
        putInRole(setting.admin(), author, child);
        String template = templateWithSheet(author.browser(), "WI", "PRD");

        assertThat(listed(author.browser())).contains(template);
        assertThat(listed(manager.browser())).as("a manager above them in the hierarchy reads nothing of theirs")
                .doesNotContain(template);
        assertThat(version(manager.browser(), template, 1).status()).isEqualTo(404);
        assertThat(listed(colleague.browser())).doesNotContain(template);
    }

    @Test
    void aTemplateSharedByHandWithAPersonIsReadByThemAndByNoOneElse() {
        Setting setting = setting();
        Person author = author(setting);
        Person chosen = reader(setting);
        Person other = reader(setting);
        String template = templateWithSheet(author.browser(), "WI", "PRD");

        Response shared = author.browser().request("PUT", RECORDS + "/" + template + "/shares",
                "{\"granteeType\":\"USER\",\"granteeId\":\"" + chosen.userId() + "\",\"accessLevel\":\"READ\"}");

        assertThat(shared.status()).as(shared.body()).isEqualTo(200);
        assertThat(listed(chosen.browser())).contains(template);
        assertThat(listed(other.browser())).doesNotContain(template);
        assertThat(version(chosen.browser(), template, 1).status()).as("a draft is not shown by a share")
                .isEqualTo(404);
    }

    // ---- roles ----

    private static String role(app.platform.testsupport.TestBrowser admin, String name, String parent) {
        Response created = admin.postJson("/api/v1/roles", "{\"name\":\"" + name + "\",\"description\":\"\""
                + (parent == null ? "" : ",\"parentId\":\"" + parent + "\"") + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    private static void putInRole(app.platform.testsupport.TestBrowser admin, Person person, String role) {
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/role",
                "{\"roleId\":\"" + role + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
    }
}
