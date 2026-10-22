package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The versions of a sheet through the API (FA3, ADR-0173, ADR-0174, ADR-0186) against a real database: the first
 * version is blank and names the shape, a draft is saved with the row version read, a lost race is a conflict, a draft
 * already exists, the history is listed without the grids, an old version is reopened exactly as it was, and the
 * numbers are never reused. Who may do what is in {@link SheetVersionsAccessIT}.
 */
@PlatformIntegrationTest
class SheetVersionsIT extends SheetSupport {

    // ---- the first version ----

    @Test
    void theFirstVersionIsBlankInTheShapeThatWasAskedFor() {
        Setting setting = setting();
        String owner = template(setting.admin());

        Response started = start(setting.admin(), owner, 2);

        assertThat(started.status()).as(started.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(started.body(), "$.data.versionNo")).isEqualTo(1);
        assertThat(JsonPath.<String>read(started.body(), "$.data.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Integer>read(started.body(), "$.data.dimension")).isEqualTo(2);
        assertThat(JsonPath.<String>read(started.body(), "$.data.rowsMode")).isEqualTo("FIXED");
        assertThat(rowVersion(started)).isZero();
        assertThat(JsonPath.<String>read(started.body(), "$.data.gridHash")).matches("[0-9a-f]{64}");
        Response opened = read(setting.admin(), owner, 1);
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(opened.body(), "$.data.grid.formatVersion")).isEqualTo(1);
        assertThat(JsonPath.<List<?>>read(opened.body(), "$.data.grid.columns")).hasSize(1);
        assertThat(JsonPath.<List<?>>read(opened.body(), "$.data.grid.rows")).isEmpty();
        assertThat(opened.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsIsRepeatableByDefault() {
        Setting setting = setting();
        String owner = template(setting.admin());

        Response started = start(setting.admin(), owner, 1);

        assertThat(JsonPath.<String>read(started.body(), "$.data.rowsMode")).isEqualTo("REPEATABLE");
        assertThat(JsonPath.<Integer>read(started.body(), "$.data.dimension")).isEqualTo(1);
    }

    @Test
    void theFirstVersionMustNameItsShapeAndTheShapeMustBeConsistent() {
        Setting setting = setting();
        String owner = template(setting.admin());

        Response noShape = startFrom(setting.admin(), owner, null);
        Response copyOfNothing = startFrom(setting.admin(), owner, 3);
        Response unknownMode = setting.admin().postJson(versions(owner),
                "{\"dimension\":2,\"rowsMode\":\"SOMETIMES\"}");
        Response wrongMode = setting.admin().postJson(versions(owner), "{\"dimension\":1,\"rowsMode\":\"FIXED\"}");
        Response wrongDimension = setting.admin().postJson(versions(owner), "{\"dimension\":3}");

        for (Response refused : List.of(noShape, copyOfNothing, unknownMode, wrongMode, wrongDimension)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(countRows(setting.organization(), owner)).as("nothing was made").isZero();
    }

    // ---- saving a draft ----

    @Test
    void aDraftIsSavedAndReadBackAsItWasSent() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        list(grid, "columns").add(GridDocs.column("c2", "Amount", "number"));
        list(grid, "headerRows").add(GridDocs.map("id", "h1"));
        list(grid, "rows").add(GridDocs.map("id", "r1", "label", "First"));
        String c1 = (String) list(grid, "columns").get(0).get("id");
        cells(grid).put("h1:" + c1, GridDocs.map("value", "Inspection", "style", GridDocs.map("bold", true, "fontSize",
                "TITLE")));
        cells(grid).put("r1:c2", GridDocs.map("value", "12.5", "kind", "input"));

        Response saved = save(setting.admin(), owner, 1, 0, json(grid));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(rowVersion(saved)).isEqualTo(1);
        Response opened = read(setting.admin(), owner, 1);
        assertThat(JsonPath.<String>read(opened.body(), "$.data.grid.cells['h1:" + c1 + "'].style.fontSize"))
                .isEqualTo("TITLE");
        assertThat(JsonPath.<String>read(opened.body(), "$.data.grid.cells['r1:c2'].value")).isEqualTo("12.5");
        assertThat(JsonPath.<List<?>>read(opened.body(), "$.data.grid.columns")).hasSize(2);
        assertThat(JsonPath.<String>read(opened.body(), "$.data.version.gridHash"))
                .isEqualTo(JsonPath.<String>read(saved.body(), "$.data.gridHash"));
    }

    @Test
    void theSameGridSavedTwiceInADifferentOrderOfKeysHasTheSameHash() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        list(grid, "columns").add(GridDocs.column("c2", "Amount", "number"));
        cells(grid).put("h1:" + list(grid, "columns").get(0).get("id"), GridDocs.map("value", "x"));
        list(grid, "headerRows").add(GridDocs.map("id", "h1"));

        Response first = save(setting.admin(), owner, 1, 0, json(grid));
        Response second = save(setting.admin(), owner, 1, rowVersion(first),
                json(GridDocs.shuffled(grid, new java.util.Random(11))));

        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(second.body(), "$.data.gridHash"))
                .isEqualTo(JsonPath.<String>read(first.body(), "$.data.gridHash"));
        assertThat(rowVersion(second)).isEqualTo(2);
    }

    @Test
    void aSaveThatCarriesAStaleRowVersionIsAConflictAndChangesNothing() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        assertThat(save(setting.admin(), owner, 1, 0, json(grid)).status()).isEqualTo(200);
        list(grid, "columns").get(0).put("label", "Changed by the second writer");

        Response stale = save(setting.admin(), owner, 1, 0, json(grid));

        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(read(setting.admin(), owner, 1).body()).doesNotContain("Changed by the second writer");
    }

    @Test
    void anInvalidGridIsRefusedWithEveryPlaceOfTheFirstStageAndStoresNothing() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        list(grid, "columns").get(0).put("label", "<b>HOSTILEMARK</b>");
        list(grid, "columns").add(GridDocs.column("c2", "Second<i>HOSTILEMARK", "text"));
        String before = JsonPath.read(read(setting.admin(), owner, 1).body(), "$.data.version.gridHash");

        Response refused = save(setting.admin(), owner, 1, 0, json(grid));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, ?>>read(refused.body(), "$.error.fields")).containsOnlyKeys(
                "grid.columns[0].label", "grid.columns[1].label");
        assertThat(refused.body()).as("what was typed does not come back").doesNotContain("HOSTILEMARK");
        assertThat(JsonPath.<String>read(read(setting.admin(), owner, 1).body(), "$.data.version.gridHash"))
                .as("nothing was stored").isEqualTo(before);
    }

    @Test
    void aBodyThatIsNotJsonOrRepeatsAKeyIsRefusedWithoutQuotingIt() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response notJson = save(setting.admin(), owner, 1, 0, "HOSTILEMARK{{{");
        Response repeated = save(setting.admin(), owner, 1, 0, "{\"HOSTILEMARK\":1,\"HOSTILEMARK\":2}");
        Response empty = save(setting.admin(), owner, 1, 0, "");

        for (Response refused : List.of(notJson, repeated, empty)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).doesNotContain("HOSTILEMARK");
            assertThat(JsonPath.<Map<String, ?>>read(refused.body(), "$.error.fields")).containsOnlyKeys("grid");
        }
    }

    @Test
    void theShapeOfTheSheetMayChangeWhileTheFirstVersionIsTheOnlyDraft() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        grid.put("dimension", 1);
        grid.put("rowsMode", "REPEATABLE");

        Response saved = save(setting.admin(), owner, 1, 0, json(grid));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(saved.body(), "$.data.dimension")).isEqualTo(1);
    }

    // ---- a draft already exists ----

    @Test
    void asksForANewVersionWhileADraftExistsAreRefusedWithItsNumber() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        Response again = startFrom(setting.admin(), owner, null);

        assertThat(again.status()).as(again.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(again.body(), "$.error.code")).isEqualTo("CONFLICT");
        assertThat(JsonPath.<String>read(again.body(), "$.error.message")).contains("version 1");
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }

    // ---- the history ----

    @Test
    void aNewVersionIsACopyOfTheActiveOneAndTheOldOneIsReopenedExactlyAsItWas() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        list(grid, "columns").get(0).put("label", "Version one label");
        assertThat(save(setting.admin(), owner, 1, 0, json(grid)).status()).isEqualTo(200);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        String oneAsSaved = read(setting.admin(), owner, 1).body();

        Response second = startFrom(setting.admin(), owner, null);

        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(second.body(), "$.data.versionNo")).isEqualTo(2);
        assertThat(JsonPath.<String>read(second.body(), "$.data.gridHash"))
                .isEqualTo(JsonPath.<String>read(read(setting.admin(), owner, 1).body(), "$.data.version.gridHash"));
        Map<String, Object> changed = gridMap(read(setting.admin(), owner, 2));
        list(changed, "columns").get(0).put("label", "Version two label");
        assertThat(save(setting.admin(), owner, 2, 0, json(changed)).status()).isEqualTo(200);
        assertThat(read(setting.admin(), owner, 1).body()).as("version one is exactly as it was")
                .isEqualTo(oneAsSaved).contains("Version one label").doesNotContain("Version two label");
        assertThat(read(setting.admin(), owner, 2).body()).contains("Version two label");
    }

    @Test
    void aNewVersionMayBeACopyOfAnyVersionNamedAndKeepsTheShape() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "REJECTED");
        assertThat(startFrom(setting.admin(), owner, null).status()).isEqualTo(201);
        walkTo(setting.organization(), owner, 2, "IN_REVIEW", "APPROVED", "ACTIVE");

        Response fromOne = startFrom(setting.admin(), owner, 1);

        assertThat(fromOne.status()).as(fromOne.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(fromOne.body(), "$.data.versionNo")).isEqualTo(3);
        assertThat(JsonPath.<String>read(fromOne.body(), "$.data.status")).isEqualTo("DRAFT");
        assertThat(setting.admin().postJson(versions(owner), "{\"dimension\":1}").status())
                .as("a draft exists, whatever else was asked").isEqualTo(409);
        walkTo(setting.organization(), owner, 3, "IN_REVIEW", "REJECTED");
        Response wrongShape = setting.admin().postJson(versions(owner), "{\"dimension\":1}");
        Response wrongMode = setting.admin().postJson(versions(owner), "{\"rowsMode\":\"FIXED_AND_REPEATABLE\"}");
        Response nothingToCopy = startFrom(setting.admin(), owner, 9);
        for (Response refused : List.of(wrongShape, wrongMode, nothingToCopy)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(countRows(setting.organization(), owner)).as("nothing was made by the refusals").isEqualTo(3);
    }

    @Test
    void theListShowsTheNewestFirstAndReadsNoGrid() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        startFrom(setting.admin(), owner, null);
        walkTo(setting.organization(), owner, 2, "IN_REVIEW", "REJECTED");
        startFrom(setting.admin(), owner, null);

        Response list = setting.admin().get(versions(owner));

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(list.body(), "$.data.versions[*].versionNo"))
                .containsExactly(3, 2, 1);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.versions[*].status"))
                .containsExactly("DRAFT", "REJECTED", "ACTIVE");
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data.canChange")).isTrue();
        assertThat(list.body()).as("a list never carries a grid").doesNotContain("\"grid\":").doesNotContain("columns");
    }

    @Test
    void numbersAreNeverReusedWhateverBecomesOfTheVersions() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 1);
        List<Integer> seen = new ArrayList<>(List.of(1));
        for (int round = 0; round < 3; round++) {
            walkTo(setting.organization(), owner, seen.get(seen.size() - 1), "IN_REVIEW", "WITHDRAWN");
            Response next = startFrom(setting.admin(), owner, null);
            assertThat(next.status()).as(next.body()).isEqualTo(201);
            seen.add(JsonPath.<Integer>read(next.body(), "$.data.versionNo"));
        }

        assertThat(seen).containsExactly(1, 2, 3, 4);
        assertThat(countRows(setting.organization(), owner)).isEqualTo(4);
    }

    // ---- a version that left draft ----

    @Test
    void aVersionThatLeftDraftCannotBeSavedAndSaysSo() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        list(grid, "columns").get(0).put("label", "Too late");
        String before = read(setting.admin(), owner, 1).body();
        walkTo(setting.organization(), owner, 1, "IN_REVIEW");

        Response late = save(setting.admin(), owner, 1, 1, json(grid));

        assertThat(late.status()).as(late.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(late.body(), "$.error.code")).isEqualTo("CONFLICT");
        assertThat(JsonPath.<String>read(late.body(), "$.error.message")).contains("no longer a draft");
        assertThat(read(setting.admin(), owner, 1).body()).doesNotContain("Too late");
        assertThat(JsonPath.<String>read(before, "$.data.grid.columns[0].label"))
                .isEqualTo(JsonPath.<String>read(read(setting.admin(), owner, 1).body(),
                        "$.data.grid.columns[0].label"));
    }

    @Test
    void theShapeOfASheetIsFixedOnceTheFirstVersionHasLeftDraft() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "REJECTED");
        startFrom(setting.admin(), owner, null);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 2));
        grid.put("dimension", 1);
        grid.put("rowsMode", "REPEATABLE");

        Response refused = save(setting.admin(), owner, 2, 0, json(grid));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, ?>>read(refused.body(), "$.error.fields")).containsKey("grid.dimension");
    }

    @Test
    void aVersionThatDoesNotExistIsNotFound() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        assertThat(read(setting.admin(), owner, 7).status()).isEqualTo(404);
        assertThat(save(setting.admin(), owner, 7, 0, "{}").status()).isEqualTo(404);
    }
}
