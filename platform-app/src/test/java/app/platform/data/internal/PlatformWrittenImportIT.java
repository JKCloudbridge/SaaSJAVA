package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.solution.SolutionSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A field only the platform writes (FA2A, ADR-0183) through the third door of a person's writes, the import: the
 * administrator, who holds every right, cannot set it, and the row that tries is reported
 * while the others are saved. The API and bulk routes are in {@code NumberSeriesIT}.
 */
@PlatformIntegrationTest
class PlatformWrittenImportIT extends SolutionSupport {

    @Autowired
    private ImportRunner runner;

    @Test
    void anImportCannotSetTheNumberAndReportsTheRowThatTries() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String imports = "/api/v1/objects/" + OBJECT + "/imports";
        Response started = admin.postJson(imports, "{\"operation\":\"CREATE\",\"dryRun\":false}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        String id = JsonPath.read(started.body(), "$.data.id");
        BytesResponse sent = admin.bytes("PUT", imports + "/" + id + "/file",
                "name,number\nfirst,SECRET-1\nsecond,\n".getBytes(StandardCharsets.UTF_8), "Content-Type",
                "text/csv");
        assertThat(sent.status()).as(sent.text()).isEqualTo(202);

        runner.workOn(organization.id());
        Response done = admin.get(imports + "/" + id);

        assertThat(JsonPath.<Number>read(done.body(), "$.data.failedRows").intValue()).as(done.body()).isEqualTo(1);
        assertThat(JsonPath.<Number>read(done.body(), "$.data.createdRows").intValue()).isEqualTo(1);
        List<String> rows = admin.bytes("GET", imports + "/" + id + "/errors", null).text().lines().skip(1).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).startsWith("1,number: This field cannot be written.");
        assertThat(JsonPath.<List<String>>read(admin.get("/api/v1/objects/" + OBJECT + "/records").body(),
                "$.data[*].fields.number")).isEmpty();
    }
}
