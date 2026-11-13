package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Format version 2 on the sheets endpoints and the real database (SH1A, ADR-0251): a document is stored in its one
 * form with the hash of that form; a copy of a version as a new draft is the same document with the same hash; the
 * limits the screen reads state the new numbers; and the switch is a lock: an instance with format version 2 switched
 * off refuses to store, to copy and to move any document of that format, says so in fixed words, still reads what is
 * stored, and does not touch format version 1.
 */
@PlatformIntegrationTest
class SheetFormatTwoIT extends SheetSupport {

    private static TestApplication switchedOff;

    @BeforeAll
    static void startAnInstanceWithTheSwitchOff() {
        // The second property is the one SheetSupport gives the main instance: a published sheet may be copied by hand.
        switchedOff = TestApplication.start("platform.sheets.format-two.enabled=false",
                "platform.inspection.hold-published-templates=false");
    }

    @AfterAll
    static void stopIt() {
        switchedOff.close();
    }

    private TestBrowser onSwitchedOff(Setting setting) {
        return TestOrganizations.signedIn(switchedOff.port(), setting.organization().host(),
                setting.organization().admin().person());
    }

    private static String rich() {
        return json(TwoDocs.rich());
    }

    // ---- stored in one form ----

    @Test
    void aDocumentIsStoredInItsOneFormWithTheHashOfThatForm() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response saved = save(setting.admin(), owner, 1, 0, rich());

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        Response read = read(setting.admin(), owner, 1);
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.grid.formatVersion")).isEqualTo(2);
        // The database keeps the keys of its own JSON type in its own order, so the text it gives back is not the text
        // that was hashed. The hash is the hash of the canonical text: write that text again from what was read back.
        SheetGridValidator.Result again = new SheetGridValidator(SheetProperties.Limits.standard(),
                new SheetSchemas(), true).check(json(JsonPath.<Map<String, Object>>read(read.body(), "$.data.grid"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(again.problems()).isEmpty();
        assertThat(JsonPath.<String>read(read.body(), "$.data.version.gridHash"))
                .isEqualTo(SheetGridWriter.hex(again.checked().hash()));
        assertThat(JsonPath.<String>read(read.body(), "$.data.grid.sheets[0].cells['h1:c1'].look.fill"))
                .isEqualTo("#ffeecc");
    }

    @Test
    void theSameMeaningSavedInAnotherOrderOfKeysHasTheSameHash() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Response first = save(setting.admin(), owner, 1, 0, rich());
        String hash = JsonPath.read(first.body(), "$.data.gridHash");

        Response second = save(setting.admin(), owner, 1, JsonPath.<Number>read(first.body(), "$.data.version")
                .longValue(), json(GridDocs.shuffled(TwoDocs.rich(), new java.util.Random(11))));

        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(second.body(), "$.data.gridHash")).isEqualTo(hash);
    }

    @Test
    void aNewDraftCopiedFromAVersionInFormatVersionTwoIsTheSameDocumentWithTheSameHash() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        assertThat(save(setting.admin(), owner, 1, 0, rich()).status()).isEqualTo(200);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        Response one = read(setting.admin(), owner, 1);

        Response copy = startFrom(setting.admin(), owner, null);

        assertThat(copy.status()).as(copy.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(copy.body(), "$.data.gridHash"))
                .isEqualTo(JsonPath.<String>read(one.body(), "$.data.version.gridHash"));
        Response two = read(setting.admin(), owner, 2);
        assertThat(JsonPath.<Map<String, Object>>read(two.body(), "$.data.grid"))
                .as("the look, the link, the panes, the page set-up and the stated cells came with it")
                .isEqualTo(JsonPath.<Map<String, Object>>read(one.body(), "$.data.grid"));
    }

    @Test
    void whatIsRefusedIsRefusedInFixedWordsThatRepeatNothingTyped() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> document = TwoDocs.rich();
        TwoDocs.sheet(document).put("title", "<b>HOSTILEMARK</b>");

        Response refused = save(setting.admin(), owner, 1, 0, json(document));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("grid.sheets[0].title").doesNotContain("HOSTILEMARK");
        assertThat(JsonPath.<Integer>read(read(setting.admin(), owner, 1).body(), "$.data.version.version"))
                .as("nothing was stored").isEqualTo(0);
    }

    // ---- what the screen is told ----

    @Test
    void theLimitsTheScreenReadsStateTheNewNumbersAndTheSwitch() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response list = setting.admin().get(versions(owner));
        Response off = onSwitchedOff(setting).get(versions(owner));

        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxFixedTextChars")).isEqualTo(5_000);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.minColumnWidth")).isEqualTo(8);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxColumnWidth")).isEqualTo(1_000);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.minRowHeight")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxRowHeight")).isEqualTo(600);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxSheets")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxFilesPerCell")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxLinkChars")).isEqualTo(2_000);
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data.limits.formatTwoEnabled")).isTrue();
        assertThat(JsonPath.<Boolean>read(off.body(), "$.data.limits.formatTwoEnabled")).isFalse();
    }

    // ---- the switch ----

    @Test
    void withTheSwitchOffNothingStoresACopiesOrReopensADocumentOfFormatVersionTwo() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        assertThat(save(setting.admin(), owner, 1, 0, rich()).status()).isEqualTo(200);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        String other = template(setting.admin());
        firstVersion(setting.admin(), other, 2);
        TestBrowser off = onSwitchedOff(setting);

        Response saved = save(off, other, 1, 0, rich());
        Response copied = startFrom(off, owner, null);

        assertThat(saved.status()).as(saved.body()).isEqualTo(400);
        assertThat(saved.body()).contains("This sheet is still in the earlier format.");
        assertThat(JsonPath.<String>read(saved.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(copied.status()).as(copied.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(copied.body(), "$.error.message"))
                .isEqualTo("This sheet is still in the earlier format.");
        assertThat(countRows(setting.organization(), owner)).as("no copy was made").isEqualTo(1);
        assertThat(JsonPath.<Integer>read(read(setting.admin(), other, 1).body(), "$.data.grid.formatVersion"))
                .as("nothing was stored").isEqualTo(1);
    }

    @Test
    void withTheSwitchOffWhatIsStoredIsStillReadAndFormatVersionOneIsUntouched() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        assertThat(save(setting.admin(), owner, 1, 0, rich()).status()).isEqualTo(200);
        String other = template(setting.admin());
        firstVersion(setting.admin(), other, 2);
        TestBrowser off = onSwitchedOff(setting);

        Response read = read(off, owner, 1);
        Map<String, Object> grid = gridMap(read(off, other, 1));
        list(grid, "columns").get(0).put("label", "Still saves");
        Response saved = save(off, other, 1, 0, json(grid));

        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.grid.formatVersion")).isEqualTo(2);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }
}
