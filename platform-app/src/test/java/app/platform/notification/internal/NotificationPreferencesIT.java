package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.Notices;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a person chooses to receive (ADR-0156): a switch nobody touched is on, a switch that is off creates nothing, the
 * floor of the platform cannot be switched off by the API or by a row, and a choice belongs to one person in one
 * organization.
 */
@PlatformIntegrationTest
class NotificationPreferencesIT extends NotificationSupport {

    private static String change(String category, String channel, boolean enabled) {
        return "{\"choices\":[{\"category\":\"" + category + "\",\"channel\":\"" + channel + "\",\"enabled\":"
                + enabled + "}]}";
    }

    private static boolean enabled(TestBrowser browser, String category, String channel) {
        Response view = browser.get(PREFERENCES);
        return JsonPath.<List<Boolean>>read(view.body(), "$.data.choices[?(@.category=='" + category
                + "' && @.channel=='" + channel + "')].enabled").get(0);
    }

    @Test
    void everySwitchIsOnUntilTouchedAndTheAccountOnesAreFixed() {
        Organization organization = organization();
        TestBrowser browser = browser(organization, member(organization));

        Response view = browser.get(PREFERENCES);

        assertThat(view.status()).as(view.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Boolean>>read(view.body(), "$.data.choices[*].enabled")).hasSize(8)
                .containsOnly(true);
        assertThat(JsonPath.<List<Boolean>>read(view.body(),
                "$.data.choices[?(@.category=='ACCOUNT')].locked")).containsExactly(true, true);
        assertThat(JsonPath.<List<Boolean>>read(view.body(),
                "$.data.choices[?(@.category!='ACCOUNT')].locked")).containsOnly(false);
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.choices[*].categoryLabel")).doesNotContain("");
    }

    @Test
    void aSwitchThatIsOffCreatesNoNoticeOfThatCategoryAndOthersStillArrive() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        UUID user = person.person().user().id();

        Response off = browser.request("PUT", PREFERENCES, change("APPROVALS", "IN_APP", false));
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(enabled(browser, "APPROVALS", "IN_APP")).isFalse();
        assertThat(enabled(browser, "APPROVALS", "MAIL")).as("only the switch that was named changed").isTrue();

        assertThat(notify(organization, user, NoticeKind.APPROVAL_TASK, "p:" + UUID.randomUUID(),
                Map.of("process", "Large__c", "step", "Manager"))).isEqualTo(Notices.Result.SUPPRESSED);
        assertThat(workflowNotice(organization, person, "p:" + UUID.randomUUID())).isEqualTo(Notices.Result.CREATED);
        assertThat(scalar(organization, "select count(*) from notification where kind = 'APPROVAL_TASK'")).isZero();

        browser.request("PUT", PREFERENCES, change("APPROVALS", "IN_APP", true));
        assertThat(notify(organization, user, NoticeKind.APPROVAL_TASK, "p:" + UUID.randomUUID(),
                Map.of("process", "Large__c", "step", "Manager"))).isEqualTo(Notices.Result.CREATED);
    }

    @Test
    void theFloorCannotBeSwitchedOffByTheApiNorByARowAndATrialNoticeAlwaysArrives() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);

        Response refused = browser.request("PUT", PREFERENCES, change("ACCOUNT", "MAIL", false));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("choices[0].enabled").contains("cannot be switched off");
        assertThat(enabled(browser, "ACCOUNT", "MAIL")).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(organization,
                "insert into notification_preference (user_id, category, channel, enabled, created_by, updated_by) "
                        + "values (?, 'ACCOUNT', 'MAIL', false, ?, ?)", person.person().user().id(),
                person.person().user().id(), person.person().user().id())).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SQLException.class);
        assertThat(notify(organization, person.person().user().id(), NoticeKind.TRIAL_NOTICE, "p:" + UUID.randomUUID(),
                Map.of("stage", "first"))).isEqualTo(Notices.Result.CREATED);
    }

    @Test
    void anUnknownCategoryOrChannelIsRefusedWithItsPlace() {
        Organization organization = organization();
        TestBrowser browser = browser(organization, member(organization));

        Response category = browser.request("PUT", PREFERENCES, change("EVERYTHING", "MAIL", false));
        Response channel = browser.request("PUT", PREFERENCES, change("APPROVALS", "PIGEON", false));

        assertThat(category.status()).isEqualTo(400);
        assertThat(category.body()).contains("choices[0].category");
        assertThat(channel.status()).isEqualTo(400);
        assertThat(channel.body()).contains("choices[0].channel");
    }

    @Test
    void aChoiceBelongsToOnePersonInOneOrganization() {
        Organization organization = organization();
        Organization other = organization();
        Member first = member(organization);
        Member second = member(organization);
        TestBrowser mine = browser(organization, first);
        TestBrowser theirs = browser(organization, second);

        mine.request("PUT", PREFERENCES, change("WORKFLOWS", "IN_APP", false));

        assertThat(enabled(mine, "WORKFLOWS", "IN_APP")).isFalse();
        assertThat(enabled(theirs, "WORKFLOWS", "IN_APP")).as("another person").isTrue();
        assertThat(workflowNotice(organization, second, "p:" + UUID.randomUUID())).isEqualTo(Notices.Result.CREATED);
        assertThat(workflowNotice(organization, first, "p:" + UUID.randomUUID())).isEqualTo(Notices.Result.SUPPRESSED);
        assertThat(scalar(other, "select count(*) from notification_preference")).as("another organization").isZero();
    }

    @Test
    void aChangeIsAuditedAsACountAndNeverAsWhatWasChosen() {
        Organization organization = organization();
        TestBrowser browser = browser(organization, member(organization));

        browser.request("PUT", PREFERENCES, change("WORKFLOWS", "MAIL", false));

        assertThat(audited(organization, "notification.preferences.changed")).isEqualTo(1);
        assertThat(owner("select attributes::text from audit_record where event_type = ? and context_tenant_id = ?",
                "notification.preferences.changed", organization.id().value()).get(0)).contains("switches")
                .doesNotContain("WORKFLOWS").doesNotContain("MAIL");
    }
}
