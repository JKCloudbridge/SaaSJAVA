package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The revised copy of a change request is changed and downloaded on the Sheet tab (FA10, ADR-0204). The people who can
 * change the held copy (a person with an open task of the request, and the organization's administrator) may take it as
 * an Excel file whatever the template's "Allow download" option says; a reader of the published revision, a person of
 * another organization and a caller who is not signed in get nothing of it. Opening a revision by its number goes
 * through the same rule: a reader is told it does not exist, in the words of an unknown number.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class HeldCopyDownloadIT extends ChangeRequestSupport {

    private static BytesResponse download(TestBrowser browser, String template, int version) {
        return browser.bytes("GET", TEMPLATES + "/" + template + "/download?versionNo=" + version, null);
    }

    private static boolean workbook(BytesResponse response) {
        return response.body().length > 4
                && new String(response.body(), 0, 2, StandardCharsets.ISO_8859_1).equals("PK");
    }

    /** A request with its revised copy made and "Allow download" off (the default), so the option is not the reason. */
    private record Held(World world, String request, int copy) {
    }

    private Held held() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Make the walkway wider", 1);
        int copy = copied(world.approver().browser(), world.template(), request);
        return new Held(world, request, copy);
    }

    @Test
    void aPersonWithAnOpenTaskTakesTheRevisedCopyAsAnExcelFileWhateverTheOptionSays() {
        Held held = held();

        BytesResponse file = download(held.world().approver().browser(), held.world().template(), held.copy());

        assertThat(file.status()).isEqualTo(200);
        assertThat(workbook(file)).as("an Excel workbook").isTrue();
    }

    @Test
    void theOrganizationAdministratorTakesItToo() {
        Held held = held();

        BytesResponse file = download(held.world().setting().admin(), held.world().template(), held.copy());

        assertThat(file.status()).isEqualTo(200);
        assertThat(workbook(file)).isTrue();
    }

    @Test
    void aReaderOfThePublishedRevisionGetsNothingOfTheHeldCopy() {
        Held held = held();

        for (Person refused : new Person[] {held.world().reader(), held.world().colleague()}) {
            BytesResponse file = download(refused.browser(), held.world().template(), held.copy());
            assertThat(file.status()).as("a member who reads the template but is not asked to decide the request")
                    .isIn(403, 404);
            assertThat(workbook(file)).isFalse();
        }
        assertThat(download(held.world().reader().browser(), held.world().template(), 1).status())
                .as("the published revision is still refused: the option is off").isEqualTo(403);
    }

    @Test
    void aMemberWhoCannotSeeTheTemplateAndAnotherOrganizationAndAnUnknownCallerGetNothing() {
        Held held = held();
        Setting elsewhere = setting();

        assertThat(download(held.world().outsider().browser(), held.world().template(), held.copy()).status())
                .isEqualTo(404);
        assertThat(download(approver(elsewhere).browser(), held.world().template(), held.copy()).status())
                .isEqualTo(404);
        assertThat(download(new TestBrowser(port, held.world().setting().organization().host()),
                held.world().template(), held.copy()).status()).isEqualTo(401);
    }

    @Test
    void aVersionNumberThatIsNotTheHeldCopyDoesNotOpenTheDoor() {
        Held held = held();

        // The option is still off, so a number that is not the held copy is judged like any download.
        assertThat(download(held.world().colleague().browser(), held.world().template(), held.copy() + 7).status())
                .isIn(403, 404);
    }

    @Test
    void theRevisionOpensByItsNumberForThePeopleWhoDecideAndNotForAReader() {
        Held held = held();
        String path = versions(held.world().template()) + "/" + held.copy();

        assertThat(held.world().approver().browser().get(path).status()).isEqualTo(200);
        assertThat(held.world().setting().admin().get(path).status()).isEqualTo(200);
        int unknown = held.world().reader().browser().get(versions(held.world().template()) + "/" + (held.copy() + 9))
                .status();
        assertThat(held.world().reader().browser().get(path).status())
                .as("the held copy answers a reader as an unknown number does").isEqualTo(unknown);
        assertThat(unknown).isEqualTo(404);
    }

    @Test
    void aDownloadIsAuditedByTemplateRevisionAndSizeAndNeverByNameOrCell() {
        Held held = held();
        long before = audited(held.world().setting().organization(), "inspection.template.downloaded");

        assertThat(download(held.world().approver().browser(), held.world().template(), held.copy()).status())
                .isEqualTo(200);

        assertThat(audited(held.world().setting().organization(), "inspection.template.downloaded"))
                .isEqualTo(before + 1);
        String attributes = String.join(" ", owner("select attributes::text from audit_record where event_type = ? "
                + "and context_tenant_id = ?", "inspection.template.downloaded",
                held.world().setting().organization().id().value()));
        assertThat(attributes).contains("version_no", "bytes").doesNotContain("First").doesNotContain(".xlsx");
    }
}
