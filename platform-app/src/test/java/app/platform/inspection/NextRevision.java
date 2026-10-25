package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.TemplateSupport.Person;
import app.platform.inspection.TemplateSupport.Setting;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes a published template's next revision the only way the product does (FA8, ADR-0202), for the tests of other
 * parts
 * that need two revisions in use: a change request, the copy of the revision in use with the sheet the test gives, and
 * the
 * last approval that publishes it. The organization gets one process for change requests, with one step, the first
 * time; the person who decides must hold the Approver and Publisher policies and the department the template is
 * published to must be {@code PLANT1/PRD}.
 */
final class NextRevision {

    private static final Set<UUID> WITH_PROCESS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger NUMBERS = new AtomicInteger();

    private NextRevision() {
    }

    /**
     * Publishes the next revision of a template and returns the version number it was made as.
     *
     * @param setting the organization and its administrator
     * @param asker anyone who can read the template
     * @param decider holds the task of the one step, the Approver and the Publisher policies
     * @param template the published template
     * @param grid the sheet the next revision will have
     */
    static int inUse(Setting setting, TestBrowser asker, Person decider, String template, String grid) {
        if (WITH_PROCESS.add(setting.organization().id().value())) {
            ChangeRequestSupport.reviewProcess(setting.admin(), "Changes" + System.nanoTime(),
                    TemplateSupport.step("Decide", TemplateSupport.user(decider.userId())));
        }
        Response active = asker.get(TemplateSupport.versions(template));
        assertThat(active.status()).as(active.body()).isEqualTo(200);
        List<Integer> inUse = JsonPath.read(active.body(), "$.data.versions[?(@.status=='ACTIVE')].versionNo");
        Response made = asker.postJson(ChangeRequestSupport.changes(template), "{\"versionNo\":" + inUse.get(0)
                + ",\"register\":{\"requestNo\":\"DCN-" + NUMBERS.incrementAndGet()
                + "\",\"changeRequested\":\"A new revision\"},\"comments\":[]}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String request = JsonPath.read(made.body(), "$.data.id");
        int copy = ChangeRequestSupport.copied(decider.browser(), template, request);
        Response read = TemplateSupport.version(decider.browser(), template, copy);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        Response saved = TemplateSupport.saveGrid(decider.browser(), template, copy, TemplateSupport.rowVersion(read),
                grid);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        String approval = ChangeRequestSupport.approvalOf(decider.browser(), ChangeRequestSupport.reviewIdOf(made));
        Response last = ChangeRequestSupport.approveWith(decider.browser(), approval,
                ChangeRequestSupport.PUBLISH_CHOICE);
        assertThat(last.status()).as(last.body()).isEqualTo(200);
        return copy;
    }
}
