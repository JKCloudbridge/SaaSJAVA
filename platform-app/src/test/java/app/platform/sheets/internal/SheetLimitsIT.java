package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The limits of the sheets endpoints (FA3, ADR-0173 section 3, ADR-0186), tried on an instance started with small
 * numbers: the size of a grid is checked from the length the client announces and again on the bytes read (so a body
 * with no announced length is bounded too), before anything is parsed; a request that carries no grid is held to a
 * small bound; and a sheet has only so many versions, because none is ever deleted.
 */
@PlatformIntegrationTest
class SheetLimitsIT extends SheetSupport {

    private static final int MOST_BYTES = 2_000;
    private static TestApplication small;

    @BeforeAll
    static void startAnInstanceWithSmallLimits() {
        small = TestApplication.start("platform.sheets.limits.max-bytes=" + MOST_BYTES,
                "platform.sheets.limits.max-versions-per-owner=3", "platform.sheets.limits.max-cell-chars=2000",
                "platform.sheets.small-body-bytes=256");
    }

    @AfterAll
    static void stopIt() {
        small.close();
    }

    private TestBrowser onSmall(Setting setting) {
        return TestOrganizations.signedIn(small.port(), setting.organization().host(),
                setting.organization().admin().person());
    }

    private static String gridOfSize(int bytes) {
        Map<String, Object> grid = GridDocs.minimal();
        GridDocs.cells(grid).put("r1:c1", GridDocs.map("value", "x"));
        int fixed = GridDocs.bytes(grid).length;
        GridDocs.cells(grid).put("r1:c1", GridDocs.map("value", "x".repeat(1 + bytes - fixed)));
        assertThat(GridDocs.bytes(grid)).hasSize(bytes);
        return GridDocs.json(grid);
    }

    // ---- the size of a grid ----

    @Test
    void aGridAboveTheLimitIsRefusedByItsAnnouncedLengthBeforeItIsRead() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser browser = onSmall(setting);
        assertThat(start(browser, owner, 2).status()).isEqualTo(201);

        Response refused = save(browser, owner, 1, 0, "x".repeat(MOST_BYTES + 1));

        assertThat(refused.status()).as(refused.body()).isEqualTo(413);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("2000");
    }

    @Test
    void aGridAboveTheLimitWithNoAnnouncedLengthIsRefusedOnTheBytesRead() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser browser = onSmall(setting);
        assertThat(start(browser, owner, 2).status()).isEqualTo(201);
        byte[] body = ("{\"x\":\"" + "x".repeat(MOST_BYTES * 20) + "\"}").getBytes(StandardCharsets.UTF_8);

        Response refused = browser.chunked("PUT", versions(owner) + "/1/grid?version=0", body);

        assertThat(refused.status()).as(refused.body()).isEqualTo(413);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aGridExactlyAtTheLimitIsReadAndJudgedOnItsContent() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser browser = onSmall(setting);
        assertThat(start(browser, owner, 2).status()).isEqualTo(201);

        // Not a valid grid for this sheet (its columns are not the sheet's), but it is read, not refused for its size.
        Response judged = save(browser, owner, 1, 0, gridOfSize(MOST_BYTES));
        Response chunked = browser.chunked("PUT", versions(owner) + "/1/grid?version=0",
                gridOfSize(MOST_BYTES).getBytes(StandardCharsets.UTF_8));

        assertThat(judged.status()).as(judged.body()).isEqualTo(200);
        assertThat(chunked.status()).as("the same, with no announced length: " + chunked.body()).isEqualTo(409);
    }

    @Test
    void theLimitOfAnOrganizationOnTheOrdinaryInstanceIsTheProductCap() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response refused = save(setting.admin(), owner, 1, 0, "x".repeat(1_000_001));

        assertThat(refused.status()).as(refused.body()).isEqualTo(413);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("1000000");
    }

    // ---- a request that carries no grid ----

    @Test
    void aRequestThatCarriesNoGridIsHeldToASmallBound() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser browser = onSmall(setting);

        Response refused = browser.postJson(versions(owner), "{\"dimension\":2,\"rowsMode\":\"" + "x".repeat(500)
                + "\"}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(413);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    // ---- the number of versions ----

    @Test
    void aSheetHasOnlySoManyVersionsAndTheLastOneIsRefusedInPlainWords() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser browser = onSmall(setting);
        assertThat(start(browser, owner, 2).status()).isEqualTo(201);
        for (int number = 1; number <= 2; number++) {
            walkTo(setting.organization(), owner, number, "IN_REVIEW", "WITHDRAWN");
            assertThat(startFrom(browser, owner, null).status()).isEqualTo(201);
        }
        walkTo(setting.organization(), owner, 3, "IN_REVIEW", "WITHDRAWN");

        Response refused = startFrom(browser, owner, null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("most versions").contains("3");
        assertThat(countRows(setting.organization(), owner)).isEqualTo(3);
    }
}
