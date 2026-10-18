package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContext;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The in-app notices of a member (ADR-0154): made once however often the cause repeats, read, counted and marked read
 * by the member alone, never visible to another member or another organization, with words built when they are read,
 * and removed by the retention only after the floor.
 */
@PlatformIntegrationTest
class NotificationIT extends NotificationSupport {

    private static final String KEY = "test:";

    private static String key() {
        return KEY + UUID.randomUUID();
    }

    @Test
    void aNoticeIsMadeForAnActiveMemberAndReadByThemWithTheWordsOfItsKind() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);

        assertThat(workflowNotice(organization, person, key())).isEqualTo(Notices.Result.CREATED);

        Response list = browser.get(NOTIFICATIONS);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].title"))
                .containsExactly("A record needs your attention");
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].body")).contains("asks you to look")
                .doesNotContain("{");
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].category")).isEqualTo("WORKFLOWS");
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data[0].read")).isFalse();
        assertThat(unread(browser)).isEqualTo(1);
    }

    @Test
    void theSameCauseMakesOneNoticeHoweverOftenItRepeats() {
        Organization organization = organization();
        Member person = member(organization);
        String key = key();

        assertThat(workflowNotice(organization, person, key)).isEqualTo(Notices.Result.CREATED);
        assertThat(workflowNotice(organization, person, key)).isEqualTo(Notices.Result.DUPLICATE);
        assertThat(workflowNotice(organization, person, key)).isEqualTo(Notices.Result.DUPLICATE);

        assertThat(scalar(organization, "select count(*) from notification where recipient_id = ?",
                person.person().user().id())).isEqualTo(1);
        assertThat(workflowNotice(organization, person, key())).as("another cause, another notice")
                .isEqualTo(Notices.Result.CREATED);
    }

    @Test
    void aPersonWhoIsNotAnActiveMemberOfTheOrganizationGetsNothing() {
        Organization organization = organization();
        Organization other = organization();
        Member stranger = member(other);

        assertThat(workflowNotice(organization, stranger, key())).isEqualTo(Notices.Result.SUPPRESSED);
        assertThat(scalar(organization, "select count(*) from notification")).isZero();
    }

    @Test
    void aMemberMarksOneNoticeReadAndThenAllOfThem() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        workflowNotice(organization, person, key());
        workflowNotice(organization, person, key());
        workflowNotice(organization, person, key());
        List<String> ids = idsOf(browser);

        Response one = browser.post(NOTIFICATIONS + "/" + ids.get(0) + "/read");
        assertThat(one.status()).as(one.body()).isEqualTo(204);
        assertThat(unread(browser)).isEqualTo(2);
        assertThat(browser.post(NOTIFICATIONS + "/" + ids.get(0) + "/read").status()).as("again changes nothing")
                .isEqualTo(204);
        assertThat(unread(browser)).isEqualTo(2);

        assertThat(browser.post(NOTIFICATIONS + "/read-all").status()).isEqualTo(204);
        assertThat(unread(browser)).isZero();
        assertThat(JsonPath.<List<Boolean>>read(browser.get(NOTIFICATIONS + "?unread=true").body(), "$.data[*].read"))
                .isEmpty();
    }

    @Test
    void aMemberNeverSeesCountsOrMarksTheNoticeOfAnotherMember() {
        Organization organization = organization();
        Member mine = member(organization);
        Member theirs = member(organization);
        TestBrowser me = browser(organization, mine);
        workflowNotice(organization, theirs, key());
        String theirNotice = idsOf(browser(organization, theirs)).get(0);

        assertThat(titles(me)).isEmpty();
        assertThat(unread(me)).isZero();
        Response foreign = me.post(NOTIFICATIONS + "/" + theirNotice + "/read");
        Response unknown = me.post(NOTIFICATIONS + "/" + UUID.randomUUID() + "/read");
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(foreign.body(), "$.error.code"))
                .as("a notice of someone else answers like one that does not exist")
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.code"));
        assertThat(JsonPath.<String>read(foreign.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        assertThat(scalar(organization, "select count(*) from notification where read_at is not null")).isZero();
    }

    @Test
    void anotherOrganizationSeesNothingOfIt() {
        Organization a = organization();
        Organization b = organization();
        Member inA = member(a);
        Member inB = member(b);
        workflowNotice(a, inA, key());

        assertThat(titles(browser(b, inB))).isEmpty();
        assertThat(unread(browser(b, inB))).isZero();
        assertThat(scalar(b, "select count(*) from notification")).isZero();
    }

    @Test
    void theWordsAreBuiltWhenTheyAreReadSoANewTextReachesOldNotices() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser admin = adminOf(organization);
        TestBrowser browser = browser(organization, person);
        workflowNotice(organization, person, key());
        assertThat(titles(browser)).containsExactly("A record needs your attention");

        Response written = admin.postJson(TEMPLATES, "{\"kind\":\"WORKFLOW_NOTICE\",\"title\":\"Please look\","
                + "\"body\":\"The workflow {workflow} of {organization} asks you to look.\"}");
        assertThat(written.status()).as(written.body()).isEqualTo(201);

        Response list = browser.get(NOTIFICATIONS);
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].title")).isEqualTo("Please look");
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].body")).startsWith("The workflow")
                .contains("asks you");
    }

    @Test
    void theListPagesNewestFirstWithACursor() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        for (int i = 0; i < 5; i++) {
            workflowNotice(organization, person, key());
        }

        Response first = browser.get(NOTIFICATIONS + "?limit=2");
        assertThat(JsonPath.<List<String>>read(first.body(), "$.data[*].id")).hasSize(2);
        assertThat(JsonPath.<Boolean>read(first.body(), "$.pagination.hasMore")).isTrue();
        String cursor = JsonPath.read(first.body(), "$.pagination.nextCursor");
        Response second = browser.get(NOTIFICATIONS + "?limit=2&cursor=" + cursor);
        Response third = browser.get(NOTIFICATIONS + "?limit=2&cursor="
                + JsonPath.<String>read(second.body(), "$.pagination.nextCursor"));

        List<String> all = new java.util.ArrayList<>(JsonPath.<List<String>>read(first.body(), "$.data[*].id"));
        all.addAll(JsonPath.<List<String>>read(second.body(), "$.data[*].id"));
        all.addAll(JsonPath.<List<String>>read(third.body(), "$.data[*].id"));
        assertThat(all).hasSize(5).doesNotHaveDuplicates().containsExactlyElementsOf(idsOf(browser));
        assertThat(JsonPath.<Boolean>read(third.body(), "$.pagination.hasMore")).isFalse();
        assertThat(browser.get(NOTIFICATIONS + "?cursor=garbage").status()).isEqualTo(400);
    }

    @Test
    void theLinkLeadsToTheRecordOrTheInboxAndNowhereElse() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        UUID record = UUID.randomUUID();
        notifyAboutRecord(organization, person.person().user().id(), NoticeKind.WORKFLOW_NOTICE, key(),
                Map.of("workflow", "Remind__c"), "ObjectA__c", record);
        notifyAboutRecord(organization, person.person().user().id(), NoticeKind.APPROVAL_TASK, key(),
                Map.of("process", "Large__c", "step", "Manager"), "ObjectA__c", record);

        List<String> hrefs = JsonPath.read(browser.get(NOTIFICATIONS).body(), "$.data[*].href");

        assertThat(hrefs).containsExactlyInAnyOrder("/records/ObjectA__c/" + record, "/approvals");
    }

    @Test
    void theRetentionRemovesOldReadNoticesAndKeepsTheYoungAndTheUnreadOnes() {
        Organization organization = organization();
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        workflowNotice(organization, person, key());
        workflowNotice(organization, person, key());
        browser.post(NOTIFICATIONS + "/" + idsOf(browser).get(0) + "/read");

        // Nothing is as old as a week: the floor keeps everything, however eager the settings are.
        int removedByDefaults = contexts.call(TenantContext.of(organization.id()), () -> transaction.execute(
                status -> store.purge(Duration.ofDays(30), Duration.ofDays(180), 100)));
        assertThat(removedByDefaults).isZero();

        // With a cutoff of nothing, the read one goes and the unread one stays (it has the longer period).
        int removed = contexts.call(TenantContext.of(organization.id()), () -> transaction.execute(
                status -> store.purge(Duration.ZERO, Duration.ofDays(180), 100)));
        assertThat(removed).isEqualTo(1);
        assertThat(scalar(organization, "select count(*) from notification")).isEqualTo(1);
        assertThat(scalar(organization, "select count(*) from notification where read_at is null")).isEqualTo(1);
    }

    @Test
    void theRowsHoldFactsAndNeverText() {
        Organization organization = organization();
        Member person = member(organization);
        workflowNotice(organization, person, key());

        assertThat(scalar(organization, "select count(*) from notification where facts::text like '%needs your%'"))
                .isZero();
        assertThat(scalar(organization, "select count(*) from notification where facts ->> 'workflow' = 'Remind__c'"))
                .isEqualTo(1);
    }
}
