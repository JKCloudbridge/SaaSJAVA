package app.platform.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.secrets.SecretValue;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;

/**
 * The seams of the webhooks (Sprint 32, ADR-0274 to ADR-0279): only the integration module opens a socket, only
 * {@code WebhookCaller} calls out for a webhook, only {@code WebhookSigner} reveals a signing secret, only the worker
 * enters the dispatch scope and claims, the handler of an event only writes a row, a webhook adds no second rule of who
 * may see a record, and no module edge changed.
 */
@Tag("architecture")
class WebhooksArchitectureTest {

    private static final String INTERNAL = "app.platform.integration.internal";
    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    @Test
    void onlyTheCallerOfTheWebhooksReachesTheOneOutboundClient() {
        noClasses().that().haveSimpleNameStartingWith("Webhook").and().doNotHaveSimpleName("WebhookCaller")
                .should().dependOnClassesThat().haveFullyQualifiedName(INTERNAL + ".EgressClient")
                .because("every webhook call goes through one class, so every address rule applies once (ADR-0277)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void noWebhookClassOpensASocketOrUsesTheJdkHttpClient() {
        noClasses().that().haveSimpleNameStartingWith("Webhook").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.Socket").allowEmptyShould(true).check(platform);
        noClasses().that().haveSimpleNameStartingWith("Webhook").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.http.HttpClient").allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheSignerRevealsASigningSecret() {
        noClasses().that().haveSimpleNameStartingWith("Webhook").and().doNotHaveSimpleName("WebhookSigner")
                .should().callMethod(SecretValue.class, "reveal")
                .because("the signing secret is read back in one place, at the moment of signing (ADR-0277)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheWorkerEntersTheWebhookDispatchScopeAndClaimsDeliveries() {
        noClasses().that().doNotHaveSimpleName("WebhookWorker").and().doNotHaveSimpleName("SystemScope")
                .should().accessField("app.platform.tenant.SystemScope", "WEBHOOK_DISPATCH")
                .because("the scope lets one statement claim the deliveries of every organization (ADR-0279)")
                .allowEmptyShould(true).check(platform);
        noClasses().that().doNotHaveSimpleName("WebhookWorker").should()
                .callMethod(INTERNAL + ".WebhookDeliveryStore", "claim", "int", "int", "java.time.Duration")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theHandlerOfAnEventOnlyWritesARowAndNeverCallsOutOrJudgesWhoMaySee() {
        noClasses().that().haveSimpleName("WebhookEventHandler").should().dependOnClassesThat()
                .haveSimpleName("WebhookCaller").orShould().dependOnClassesThat().haveSimpleName("WebhookSigner")
                .orShould().dependOnClassesThat().haveSimpleName("WebhookVisibility")
                .orShould().dependOnClassesThat().haveSimpleName("WebhookGate")
                .orShould().dependOnClassesThat().haveSimpleName("EgressClient")
                .because("a handler writes a row and returns; the call is made later by the worker (ADR-0278)")
                .check(platform);
    }

    @Test
    void aWebhookAddsNoSecondRuleOfWhoMaySeeARecordAndWritesNoRecord() {
        noClasses().that().haveSimpleNameStartingWith("Webhook").should().dependOnClassesThat()
                .haveFullyQualifiedName("app.platform.data.BackgroundWrites").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("app.platform.data.Records").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("app.platform.data.BackgroundRecords")
                .because("who may see a record is the decision API's, never a rule of a webhook (ADR-0115, ADR-0277)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheStoresOfTheWebhooksNameTheirTables() {
        // The statement scan of OnlyTheQueueStoresNameTheTablesTest holds the text; this holds the dependency.
        noClasses().that().haveSimpleNameStartingWith("Webhook").and().haveNameNotMatching(".*Store")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theIntegrationModuleStillDependsOnTheSameModulesAsBefore() throws ClassNotFoundException {
        ApplicationModule module = Class.forName("app.platform.integration.package-info")
                .getAnnotation(ApplicationModule.class);

        assertThat(Arrays.asList(module.allowedDependencies())).containsExactlyInAnyOrder("sharedkernel", "tenant",
                "metadata", "security", "data", "licensing", "expression", "observability");
    }

    @Test
    void theModulesThatCountOrConsoleTheLimitsGainedNoEdge() throws ClassNotFoundException {
        assertThat(Arrays.asList(Class.forName("app.platform.publicapi.package-info")
                .getAnnotation(ApplicationModule.class).allowedDependencies()))
                .containsExactlyInAnyOrder("data", "tenant", "sharedkernel", "observability");
        assertThat(Arrays.asList(Class.forName("app.platform.platformadmin.package-info")
                .getAnnotation(ApplicationModule.class).allowedDependencies()))
                .containsExactlyInAnyOrder("sharedkernel", "tenant", "identity", "licensing", "security", "audit");
    }
}
