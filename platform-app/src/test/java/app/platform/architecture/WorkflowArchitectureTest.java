package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What keeps workflows from becoming a second path to the platform (Sprint 22, ADR-0133 to ADR-0137; Sprint 23,
 * ADR-0138 to ADR-0144). The module graph itself is verified by the module tests; these are the rules that graph cannot
 *say. /
@Tag("architecture")
class WorkflowArchitectureTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    @Test
    void theRecordStoreAndTheWritePathKnowNothingOfWorkflows() {
        // Nothing is added to the request that writes a record (ADR-0133): the only coupling is the event.
        noClasses().that().resideInAPackage("app.platform.data..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.workflow..")
                .because("a save is never slower or less reliable because of a workflow")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theWorkflowModuleDoesNotUseTheExpressionEngineOrTheOutboxDirectly() {
        // It receives compiled conditions through the metadata contract (ADR-0136) and reacts to events through the
        // handler contract of the shared kernel (ADR-0016).
        noClasses().that().resideInAPackage("app.platform.workflow..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.expression..", "app.platform.outbox..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theWorkflowModuleReadsRecordsOnlyThroughTheBackgroundContract() {
        // The platform's own reads of records go through the query engine's stages (ADR-0125), never to storage.
        noClasses().that().resideInAPackage("app.platform.workflow..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.StorageStrategy")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.Records")
                .because("a workflow reads through BackgroundRecords and writes through BackgroundWrites, the one "
                        + "reviewed place where background work changes a record (ADR-0139)")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheWorkflowAndApprovalModulesCanChangeARecordAsThePlatform() {
        // BackgroundWrites skips the member gate and the permissions, so its callers are a reviewed list of two (the
        // workflow actions, ADR-0139, and the outcome update of an approval, ADR-0149): no
        // controller, import, bulk or other module reaches it (ADR-0139). The data module implements it.
        outsidersMayNotUseTheBackgroundDoor().check(platform);
        noClasses().that().resideInAPackage("app.platform.data.internal..")
                .and().haveSimpleNameNotContaining("BackgroundWrites")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("app.platform.data.internal.DefaultBackgroundWrites")
                .because("the platform door is entered by the interface only")
                .allowEmptyShould(true)
                .check(platform);
    }

    private static ArchRule outsidersMayNotUseTheBackgroundDoor() {
        // The module of a shipped solution is the third (FA4, ADR-0188): it sets the one field only the platform writes
        // when it retires what it keeps. The lock still refuses it, and its fields are a short fixed list.
        return noClasses().that().resideOutsideOfPackages("app.platform.workflow..", "app.platform.approval..",
                "app.platform.data..", "app.platform.inspection..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.BackgroundWrites")
                .because("background writes are for workflows, the outcome of an approval and a solution's own act "
                        + "only")
                .allowEmptyShould(true);
    }

    @Test
    void aClassOfABusinessModuleThatTakesTheBackgroundDoorBreaksTheRule() {
        // FA4A, ADR-0187: the hook of a business module writes through the narrow writer, never through this door. The
        // rule above is held to a class that does the forbidden thing, so it is known to fail when it should.
        JavaClasses clusterLike = new ClassFileImporter()
                .importClasses(app.platform.architecture.fixtures.ClusterLikeWriter.class);

        assertThat(outsidersMayNotUseTheBackgroundDoor().evaluate(clusterLike).hasViolation()).isTrue();
    }

    @Test
    void theWorkflowModuleSendsMailOnlyThroughTheQueueOfTheSharedKernel() {
        // The queue holds facts; the notification module builds and sends (ADR-0024). Nothing in workflows knows how.
        noClasses().that().resideInAPackage("app.platform.workflow..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.notification..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void parkedEventsAreReachedThroughTheSharedKernelContractOnly() {
        // The outbox implements ParkedEvents; nothing outside it names its classes (ADR-0143).
        noClasses().that().resideOutsideOfPackages("app.platform.outbox..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.outbox.internal.OutboxParkedEvents")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheMetadataModuleReadsOrWritesTheDefinitionTablesOfWorkflows() {
        noClasses().that().resideOutsideOfPackages("app.platform.metadata..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.metadata.internal.WorkflowStore")
                .allowEmptyShould(true)
                .check(platform);
    }
}
