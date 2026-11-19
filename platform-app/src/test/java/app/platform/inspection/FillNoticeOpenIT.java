package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A bell row about a fill-in opens the fill-in (SH_6). The notice names the fill-in's header record; the page that
 * shows it is the fill-ins page of the application, and the answer is decided by the same read gate as opening the
 * record, so a person who may not read the header gets the plain message, the same as for an unknown id or another
 * organization. The approvals inbox asks the same destinations, so it opens a fill-in on its own page too.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillNoticeOpenIT extends FillSupport {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String INBOX = "/api/v1/approvals/inbox?tab=TO_APPROVE";
    private static final String PLAIN = "no record you can open";

    @Autowired
    private Notices notices;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private TransactionTemplate transaction;

    /** Makes the notice the way the Action plan code does (the fill-in's header, the page parts as facts). */
    private void tell(Setting setting, Person person, String record) {
        contexts.call(TenantContext.of(setting.organization().id()), () -> transaction.execute(status ->
                notices.notify(new NoticeRequest(NoticeKind.ACTION_PLAN_NEEDED, person.member().person().user().id(),
                        FILL, UUID.fromString(record),
                        Map.of("event", "action-plan", "application", "inspection", "entry", "s-fill-ins"),
                        "action-plan:" + record))));
    }

    private static String noticeOf(Person person, String kind) {
        Response list = person.browser().get(NOTIFICATIONS);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        List<String> ids = JsonPath.read(list.body(), "$.data[?(@.kind == '" + kind + "')].id");
        assertThat(ids).as("the person has a " + kind + " notice").isNotEmpty();
        return ids.getFirst();
    }

    private static Response open(Person person, String notice) {
        Response opened = person.browser().postJson(NOTIFICATIONS + "/" + notice + "/open", "{}");
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        return opened;
    }

    private static String page(String fill) {
        return "^/apps/[a-z-]+/s-fill-ins\\?open=" + fill + "$";
    }

    private static void assertPlain(Response opened) {
        assertThat(JsonPath.<String>read(opened.body(), "$.data.href")).isEqualTo("/notifications");
        assertThat(JsonPath.<String>read(opened.body(), "$.data.explanation")).contains(PLAIN);
    }

    @Test
    void theFillersNoticeOpensTheFillInAndAPersonWhoCannotReadItGetsThePlainMessage() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        tell(world.setting(), world.filler(), fill);
        Response led = open(world.filler(), noticeOf(world.filler(), "ACTION_PLAN_NEEDED"));
        assertThat(JsonPath.<String>read(led.body(), "$.data.href")).matches(page(fill));

        // A colleague of the same department cannot read the filler's private header: nothing opens, nothing is said
        // about why.
        tell(world.setting(), world.colleague(), fill);
        assertPlain(open(world.colleague(), noticeOf(world.colleague(), "ACTION_PLAN_NEEDED")));

        // An id that does not exist is answered in the same words.
        tell(world.setting(), world.reviewer(), UUID.randomUUID().toString());
        assertPlain(open(world.reviewer(), noticeOf(world.reviewer(), "ACTION_PLAN_NEEDED")));
    }

    @Test
    void aPersonOfAnotherOrganizationGetsThePlainMessage() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        Setting other = setting();
        Person stranger = outsider(other);

        tell(other, stranger, fill);

        assertPlain(open(stranger, noticeOf(stranger, "ACTION_PLAN_NEEDED")));
    }

    @Test
    void aReviewersApprovalRowOpensTheFillInOnlyForSomeoneWhoMayReadItAndSoDoesTheInbox() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        String request = sent(world.filler().browser(), fill, world.process());
        for (Person asked : List.of(world.approver(), world.checker())) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    !JsonPath.<List<String>>read(asked.browser().get(NOTIFICATIONS).body(),
                            "$.data[?(@.kind == 'APPROVAL_TASK')].id").isEmpty());
        }

        // The approver holds the policy that reads fill-ins: the row and the inbox lead to the fill-in's own page.
        Response opened = open(world.approver(), noticeOf(world.approver(), "APPROVAL_TASK"));
        assertThat(JsonPath.<String>read(opened.body(), "$.data.href")).matches(page(fill));
        // The inbox asks the same destinations (before this change it fell back to the plain record page); the page
        // decides again with the person's own rights.
        Response inbox = world.approver().browser().get(INBOX);
        assertThat(inbox.status()).as(inbox.body()).isEqualTo(200);
        List<String> paths = JsonPath.read(inbox.body(), "$.data[?(@.instance.id == '" + request + "')].path");
        assertThat(paths).singleElement().asString().matches(page(fill));

        // A task grants no access to the record: a person who is asked to decide but holds no policy that reads the
        // fill-in gets the plain message, and the inbox names no page for it.
        assertPlain(open(world.checker(), noticeOf(world.checker(), "APPROVAL_TASK")));
        Response checkers = world.checker().browser().get(INBOX);
        assertThat(JsonPath.<List<Boolean>>read(checkers.body(), "$.data[*].recordReadable")).containsExactly(false);
        assertThat(checkers.body()).doesNotContain("s-fill-ins");
    }
}
