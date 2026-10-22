package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who may do what with a sheet (FA3, ADR-0186): every endpoint, as the person who may change the record, the person who
 * may only read it, a person who may not read it, a person of another organization, a platform administrator, someone
 * who is not signed in, and someone who forges the organization. A sheet has no permission of its own; it follows the
 * record it belongs to, so a share of the record reaches the sheet and nothing else does, and a version still being
 * written is never shown to a person who may only read.
 */
@PlatformIntegrationTest
class SheetVersionsAccessIT extends SheetSupport {

    private static final String GRID_OF_ANOTHER = "{}";

    /** A sheet with an active version 1 and a draft version 2. */
    private record Sheet(Setting setting, String owner) {
    }

    private Sheet sheetWithActiveAndDraft() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        assertThat(startFrom(setting.admin(), owner, null).status()).isEqualTo(201);
        return new Sheet(setting, owner);
    }

    // ---- the person who may change the record ----

    @Test
    void aMemberWhoMayUpdateTheRecordReadsStartsAndSaves() {
        Setting setting = setting();
        String owner = template(setting.admin());
        Member member = author(setting);
        TestBrowser author = as(setting.organization(), member);

        Response started = start(author, owner, 2);
        Response list = author.get(versions(owner));
        Response opened = read(author, owner, 1);
        Response saved = save(author, owner, 1, 0, gridOf(opened));

        assertThat(started.status()).as(started.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data.canChange")).isTrue();
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.versions[*].status")).containsExactly("DRAFT");
        assertThat(opened.status()).isEqualTo(200);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }

    // ---- the person who may only read the record ----

    @Test
    void aMemberWhoMayOnlyReadSeesTheVersionsThatAreInUseAndNeverTheDraft() {
        Sheet sheet = sheetWithActiveAndDraft();
        TestBrowser reader = as(sheet.setting().organization(), reader(sheet.setting()));

        Response list = reader.get(versions(sheet.owner()));
        Response active = read(reader, sheet.owner(), 1);
        Response draft = read(reader, sheet.owner(), 2);

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data.canChange")).isFalse();
        assertThat(JsonPath.<List<Integer>>read(list.body(), "$.data.versions[*].versionNo")).containsExactly(1);
        assertThat(active.status()).as(active.body()).isEqualTo(200);
        assertThat(draft.status()).as("a draft answers like a version that does not exist").isEqualTo(404);
        assertThat(JsonPath.<String>read(draft.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(read(reader, sheet.owner(), 9).body(), "$.error.message"));
    }

    @Test
    void aMemberWhoMayOnlyReadSeesTheReplacedAndRetiredVersionsToo() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED");
        startFrom(setting.admin(), owner, null);
        walkTo(setting.organization(), owner, 2, "IN_REVIEW", "APPROVED", "ACTIVE", "RETIRED");
        startFrom(setting.admin(), owner, 1);
        walkTo(setting.organization(), owner, 3, "IN_REVIEW", "APPROVED");
        startFrom(setting.admin(), owner, null);
        walkTo(setting.organization(), owner, 4, "IN_REVIEW", "REJECTED");
        startFrom(setting.admin(), owner, null);
        walkTo(setting.organization(), owner, 5, "IN_REVIEW", "WITHDRAWN");
        startFrom(setting.admin(), owner, null);
        TestBrowser reader = as(setting.organization(), reader(setting));

        Response list = reader.get(versions(owner));

        assertThat(JsonPath.<List<Integer>>read(list.body(), "$.data.versions[*].versionNo"))
                .as("replaced and retired only: not approved, rejected, withdrawn or draft").containsExactly(2, 1);
        for (int number : List.of(3, 4, 5, 6)) {
            assertThat(read(reader, owner, number).status()).as("version " + number).isEqualTo(404);
        }
    }

    @Test
    void aMemberWhoMayOnlyReadCannotStartOrSaveAndIsToldSo() {
        Sheet sheet = sheetWithActiveAndDraft();
        TestBrowser reader = as(sheet.setting().organization(), reader(sheet.setting()));
        String grid = gridOf(read(sheet.setting().admin(), sheet.owner(), 2));

        Response started = start(reader, sheet.owner(), 2);
        Response saved = save(reader, sheet.owner(), 2, 0, grid);

        for (Response refused : List.of(started, saved)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
        assertThat(countRows(sheet.setting().organization(), sheet.owner())).isEqualTo(2);
    }

    // ---- a person who may not read the record ----

    @Test
    void aMemberWhoMayNotReadTheRecordGetsNotFoundEverywhereLikeForARecordThatDoesNotExist() {
        Sheet sheet = sheetWithActiveAndDraft();
        TestBrowser stranger = as(sheet.setting().organization(), plainMember(sheet.setting().organization()));
        String nothing = UUID.randomUUID().toString();

        for (String owner : List.of(sheet.owner(), nothing)) {
            assertThat(stranger.get(versions(owner)).status()).as("list " + owner).isEqualTo(404);
            assertThat(read(stranger, owner, 1).status()).as("one " + owner).isEqualTo(404);
            assertThat(start(stranger, owner, 2).status()).as("start " + owner).isEqualTo(404);
            assertThat(save(stranger, owner, 2, 0, GRID_OF_ANOTHER).status()).as("save " + owner).isEqualTo(404);
        }
        assertThat(JsonPath.<String>read(stranger.get(versions(sheet.owner())).body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(stranger.get(versions(nothing)).body(), "$.error.message"));
    }

    @Test
    void aRecordOfAnObjectThatMayNotOwnASheetHasNoSheet() {
        Setting setting = setting();
        String owner = template(setting.admin());

        for (String object : List.of("Account", "Employee__c", "User", "lowercase", "A..B")) {
            Response list = setting.admin().get("/api/v1/sheets/" + object + "/" + owner + "/versions");
            Response started = setting.admin().postJson("/api/v1/sheets/" + object + "/" + owner + "/versions",
                    "{\"dimension\":2}");
            assertThat(list.status()).as(object).isEqualTo(404);
            assertThat(started.status()).as(object).isEqualTo(404);
        }
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    // ---- other organizations, the platform, nobody, a forged host ----

    @Test
    void anotherOrganizationNeverSeesOrTouchesTheSheetEvenWithTheSameIdentifiers() {
        Sheet mine = sheetWithActiveAndDraft();
        Setting theirs = setting();
        TestBrowser outsider = theirs.admin();

        assertThat(outsider.get(versions(mine.owner())).status()).isEqualTo(404);
        assertThat(read(outsider, mine.owner(), 1).status()).isEqualTo(404);
        assertThat(start(outsider, mine.owner(), 2).status()).isEqualTo(404);
        assertThat(save(outsider, mine.owner(), 2, 0, GRID_OF_ANOTHER).status()).isEqualTo(404);
        assertThat(countRows(mine.setting().organization(), mine.owner())).isEqualTo(2);
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoSheets() {
        Sheet mine = sheetWithActiveAndDraft();
        switchOff(mine.setting().organization());

        assertThat(mine.setting().admin().get(versions(mine.owner())).status()).isEqualTo(404);
        assertThat(start(mine.setting().admin(), mine.owner(), 2).status()).isEqualTo(404);
        switchOn(mine.setting().organization());
        assertThat(mine.setting().admin().get(versions(mine.owner())).status()).as("it all comes back").isEqualTo(200);
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothing() {
        Sheet mine = sheetWithActiveAndDraft();
        Setting theirs = setting();
        String theirOwner = template(theirs.admin());
        firstVersion(theirs.admin(), theirOwner, 2);
        String[] forged = {"X-Tenant-Id", theirs.organization().id().value().toString(), "X-Forwarded-Host",
            theirs.organization().host(), "X-Organization", theirs.organization().tenant().slug()};

        Response list = mine.setting().admin().get(versions(theirOwner), forged);
        Response own = mine.setting().admin().get(versions(mine.owner()), forged);

        assertThat(list.status()).as("their sheet is not reachable from my host").isEqualTo(404);
        assertThat(own.status()).as("mine is still mine").isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(own.body(), "$.data.versions[*].versionNo")).containsExactly(2, 1);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressReachesNoSheet() {
        Sheet sheet = sheetWithActiveAndDraft();
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        assertThat(platform.get(versions(sheet.owner())).status()).isEqualTo(404);
        assertThat(start(platform, sheet.owner(), 2).status()).isEqualTo(404);
        assertThat(save(platform, sheet.owner(), 2, 0, GRID_OF_ANOTHER).status()).isEqualTo(404);
    }

    @Test
    void aPersonWhoIsNotSignedInGetsUnauthenticatedEverywhere() {
        Sheet sheet = sheetWithActiveAndDraft();
        TestBrowser nobody = new TestBrowser(port, sheet.setting().organization().host());

        Map<String, Response> answers = Map.of(
                "list", nobody.get(versions(sheet.owner())),
                "one", read(nobody, sheet.owner(), 1),
                "start", start(nobody, sheet.owner(), 2),
                "save", save(nobody, sheet.owner(), 2, 0, GRID_OF_ANOTHER));

        answers.forEach((name, answer) -> {
            assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefused() {
        Sheet sheet = sheetWithActiveAndDraft();

        Response refused = sheet.setting().admin().postJsonWithoutCsrfHeader(versions(sheet.owner()),
                "{\"dimension\":2}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(countRows(sheet.setting().organization(), sheet.owner())).isEqualTo(2);
    }

    // ---- the content type and the address ----

    @Test
    void aGridThatIsNotSentAsJsonIsRefused() {
        Sheet sheet = sheetWithActiveAndDraft();

        Response refused = sheet.setting().admin().request("PUT", versions(sheet.owner()) + "/2/grid?version=0",
                gridOf(read(sheet.setting().admin(), sheet.owner(), 2)), "Content-Type", "text/plain");

        assertThat(refused.status()).as(refused.body()).isEqualTo(415);
    }

    @Test
    void aSaveWithoutTheRowVersionIsRefused() {
        Sheet sheet = sheetWithActiveAndDraft();

        Response refused = sheet.setting().admin().request("PUT", versions(sheet.owner()) + "/2/grid",
                gridOf(read(sheet.setting().admin(), sheet.owner(), 2)));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
    }
}
