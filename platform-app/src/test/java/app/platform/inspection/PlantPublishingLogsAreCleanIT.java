package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of publishing a document to its plants and of the monthly fill-in (MC2A, ADR-0012, ADR-0260): plants
 * are published to, reviewers are chosen, changed and taken back, fill-ins are started, refused and sent, with the
 * application and the security framework logging at debug level. A plant code, the month a person typed, the name of a
 * group chosen as reviewers and a reviewer's identifier are the organization's: they are never logged, never written to
 * the audit trail apart from counts, never kept in a notice and never echoed by a refusal. The audit trail does show
 * that the act happened, by the document's identifier and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + PlantPublishingLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class PlantPublishingLogsAreCleanIT extends PlantsSupport {

    static final String LOG_FILE = "target/it-logs/plant-publishing-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    /** Every notice of the kind of the organization, whole, as the organization sees them. */
    private static String noticesOf(app.platform.sharedkernel.TenantId tenant) {
        try {
            return app.platform.testsupport.tenancy.TenantFixtures.asTenant(tenant, connection -> {
                try (var statement = connection.prepareStatement("select coalesce(string_agg("
                        + "to_jsonb(n)::text, ' '), '') from notification n where n.kind = 'DOCUMENT_AT_PLANT'");
                        var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            });
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("The notices could not be read", e);
        }
    }

    @Test
    void noPlantMonthReviewerOrGroupNameReachesALogTheTrailANoticeOrARefusal() throws IOException {
        Plants plants = plants();
        var setting = plants.world().setting();
        String month = "2011-03";
        String groupName = "reviewers-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String namedGroup = group(setting.admin(), groupName, plants.reviewerC());
        String missing = UUID.randomUUID().toString();
        List<Response> refusals = new ArrayList<>();

        Response published = publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()), reviewerGroup(namedGroup)),
                plant("PLANT2", reviewerUser(plants.reviewerB())));
        refusals.add(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", "{\"kind\":\"USER\",\"id\":\"" + missing + "\"}")));
        refusals.add(publishToPlants(plants.creator(), plants.template(),
                plant("HEAD", reviewerUser(plants.reviewerA()))));
        refusals.add(publishToPlants(plants.reviewerA().browser(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()))));
        refusals.add(publishToPlants(plants.world().approver().browser(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()))));
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", month);
        refusals.add(startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"" + month + "\"}"));
        refusals.add(startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT2\",\"period\":\"" + month + "\"}"));
        refusals.add(startMonth(plants.head1().browser(), plants.template(),
                "{\"plant\":\"PLANT1\",\"period\":\"2999-01\"}"));
        saved(plants.head1().browser(), fill, complete());
        String request = sent(plants.head1().browser(), fill, plants.process());
        Response decided = approve(plants.reviewerA().browser(), request);
        Response search = plants.creator().postJson(TEMPLATES + "/" + plants.template()
                + "/plants/reviewer-choices", "{\"text\":\"" + groupName + "\"}");
        assertThat(publishToPlants(plants.creator(), plants.template()).status()).isEqualTo(200);

        String trail = setting.admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String audit = owner("select string_agg(attributes::text, ' ') from audit_record where event_type like "
                + "'inspection.%' and context_tenant_id = ?", setting.organization().id().value()).get(0);
        String notices = noticesOf(setting.organization().id());
        List<String> secrets = List.of("PLANT1", "PLANT2", month, groupName);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(decided.status()).as(decided.body()).isEqualTo(200);
        assertThat(search.status()).isEqualTo(200);
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
        }
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(audit).as("not in the audit records of the cluster: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
            for (Response refused : refusals) {
                assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        assertThat(trail).as("the trail shows that it happened").contains("inspection.template.plants.published");
        assertThat(audit).contains("plants_added", "reviewers_added", "shares_made");
    }
}
