package app.platform.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The seams of the connector framework (Sprint 26, ADR-0207): the platform never names an external system, an adapter
 * is only a description and cannot reach the network, the database or a secret, and the sample connector is reached
 * only through the connector interface. Complements the platform-wide rule that only this module may reference its
 * adapter package.
 */
@Tag("architecture")
class IntegrationArchitectureTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    @Test
    void noModuleOutsideIntegrationReferencesTheAdapterPackage() {
        noClasses().that().resideOutsideOfPackage("app.platform.integration..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.integration.adapter..")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void anAdapterCannotOpenAConnectionReadASecretOrTouchTheDatabaseTheTenantOrTheRestOfTheModule() {
        noClasses().that().resideInAPackage("app.platform.integration.adapter..")
                .should().dependOnClassesThat().resideInAnyPackage("java.net..", "javax.net..", "java.sql..",
                        "org.springframework.jdbc..", "org.springframework.web..",
                        "app.platform.sharedkernel.secrets..",
                        "app.platform.tenant..", "app.platform.integration.internal..",
                        "app.platform.metadata..", "app.platform.data..", "app.platform.security..")
                .check(platform);
    }

    @Test
    void theSampleConnectorIsReachedOnlyThroughTheConnectorInterface() {
        noClasses().that().resideOutsideOfPackage("app.platform.integration.adapter.rest..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.integration.adapter.rest..")
                .check(platform);
    }

    @Test
    void onlyTheEgressClientOpensASocketAndNothingUsesTheJdkHttpClient() {
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().doNotHaveSimpleName("EgressClient")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket")
                .check(platform);
        noClasses().that().resideInAPackage("app.platform.integration..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.http.HttpClient")
                .check(platform);
    }

    @Test
    void onlyTheReceiverWritesRecordsAndNothingElseOfTheModuleCallsThePlatformsDoor() {
        // Sprint 28 (ADR-0222): a change from outside is written by the receiver and by nothing else of this module,
        // through the same pipeline as a workflow, as the platform, within the scope of the connection.
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().doNotHaveSimpleName("InboundService")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.BackgroundWrites")
                .because("only InboundService may change a record on behalf of a connection")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheSenderTheTesterAndTheirOwnTypesUseTheOutboundClient() {
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().haveNameNotMatching(".*EgressClient.*")
                .and().doNotHaveSimpleName("OutboundSender").and().doNotHaveSimpleName("ConnectionTesting")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.integration.internal.EgressClient")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theReceiverAndTheSenderNeverReadTheValueOfASecretThemselves() {
        noClasses().that().haveSimpleName("InboundService").or().haveSimpleName("OutboundSender")
                .or().haveSimpleName("InboundController").or().haveSimpleName("ConnectionTesting")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.sharedkernel.secrets.SecretValue")
                .because("the value is read by the header builder and the signature check, which wipe it")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void everyConnectorLivesInTheAdapterPackage() {
        classes().that().implement("app.platform.integration.adapter.Connector")
                .should().resideInAPackage("app.platform.integration.adapter..")
                .allowEmptyShould(true).check(platform);
    }
}
