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
    void onlyTheEgressClientAndTheSshTransportHoldASocketAndNothingUsesTheJdkHttpClient() {
        // Sprint 29A (ADR-0238): exactly one class more. The transport gets its socket from EgressClient.open, so the
        // address rules are the same, and it is the one class that hands a socket to the SSH library.
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().haveNameNotMatching(".*EgressClient.*").and().haveNameNotMatching(".*SftpTransport.*")
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
    void onlyTheSshTransportNamesTheSshLibrary() {
        noClasses().that().resideOutsideOfPackage("app.platform.integration..")
                .should().dependOnClassesThat().resideInAPackage("com.jcraft..")
                .allowEmptyShould(true).check(platform);
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().haveNameNotMatching(".*SftpTransport.*")
                .should().dependOnClassesThat().resideInAPackage("com.jcraft..")
                .because("the library is used by one class, behind FileTransport (ADR-0238)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheWorkerClaimsDuePollsAndTheLoginIsBuiltInOnePlace() {
        noClasses().that().doNotHaveSimpleName("OutboundWorker").and().doNotHaveSimpleName("PollStore")
                .should().callMethod("app.platform.integration.internal.PollStore", "claimDue", "int",
                        "java.time.Duration")
                .because("the claim reads the due polls of every organization (ADR-0242)")
                .allowEmptyShould(true).check(platform);
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().doNotHaveSimpleName("OutboundWorker").and().doNotHaveSimpleName("ConnectionTesting")
                .and().doNotHaveSimpleName("FilePoller").and().doNotHaveSimpleName("CredentialLogins")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.integration.internal.CredentialLogins")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theFileConnectorIsReachedOnlyThroughTheConnectorInterface() {
        noClasses().that().resideOutsideOfPackage("app.platform.integration.adapter.file..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.integration.adapter.file..")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheSenderTheTesterAndTheirOwnTypesUseTheOutboundClient() {
        noClasses().that().resideInAPackage("app.platform.integration..")
                .and().haveNameNotMatching(".*EgressClient.*")
                .and().doNotHaveSimpleName("SftpTransport")
                .and().doNotHaveSimpleName("OutboundWorker").and().doNotHaveSimpleName("ConnectionTesting")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.integration.internal.EgressClient")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theReceiverAndTheSenderNeverReadTheValueOfASecretThemselves() {
        noClasses().that().haveSimpleName("InboundService").or().haveSimpleName("OutboundWorker")
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

    @Test
    void onlyTheWorkerEntersTheDispatchScope() {
        noClasses().that().doNotHaveSimpleName("OutboundWorker").and().doNotHaveSimpleName("SystemScope")
                .should().accessField("app.platform.tenant.SystemScope", "INTEGRATION_DISPATCH")
                .because("the scope lets one statement claim the deliveries of every organization (ADR-0231)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theBreakerThePolicyAndTheLimiterArePure() {
        noClasses().that().resideInAPackage("app.platform.integration..").and().haveSimpleName("CircuitBreaker")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("RetryPolicy")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("RateLimiter")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("DeliveryState")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("FilePaths")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("FileBatchPlan")
                .or().resideInAPackage("app.platform.integration..").and().haveSimpleName("SshOutcome")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "java.net..",
                        "javax.sql..")
                .because("they are state machines and arithmetic, tested as properties (ADR-0229)")
                .allowEmptyShould(true).check(platform);
    }
}
