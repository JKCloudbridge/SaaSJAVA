package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What keeps approvals from becoming a second path to the platform or a cycle in the module graph (Sprint 24, ADR-0145
 * to
 * ADR-0153). The module graph itself is verified by the module tests; these are the rules that graph cannot say.
 */
@Tag("architecture")
class ApprovalArchitectureTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    @Test
    void theRecordStoreKnowsNothingOfApprovals() {
        // The lock is a contract of the shared kernel that approval implements (ADR-0148): data asks it, and never
        // depends
        // on the module that answers.
        noClasses().that().resideInAPackage("app.platform.data..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.approval..")
                .because("a write asks the shared-kernel contract RecordLocks, not the approval module")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void approvalNeverDependsOnWorkflowSoThereIsNoCycleWithStartApproval() {
        // What follows the end of an approval is a workflow that starts from the approval events (ADR-0149); approval
        // does
        // not run workflow actions itself.
        noClasses().that().resideInAPackage("app.platform.approval..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.workflow..")
                .because("a workflow reaches approval, never the other way round")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void workflowReachesApprovalOnlyThroughItsPublicInterface() {
        noClasses().that().resideInAPackage("app.platform.workflow..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.approval.internal..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theApprovalModuleDoesNotUseTheExpressionEngineTheOutboxOrTheNotificationModuleDirectly() {
        // It receives compiled criteria through the metadata contract (ADR-0145), announces through the event contract
        // (ADR-0016) and queues mail through the shared-kernel queue (ADR-0152).
        noClasses().that().resideInAPackage("app.platform.approval..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.expression..",
                        "app.platform.outbox..", "app.platform.notification..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theApprovalModuleReadsAndWritesRecordsOnlyThroughTheBackgroundContracts() {
        // Reads of records for a task are the record store's own stages (RecordTitles, BackgroundRecords); the outcome
        // update is the one reviewed write door (ADR-0139). Never the storage, never a second path (ADR-0122).
        noClasses().that().resideInAPackage("app.platform.approval..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.StorageStrategy")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.Records")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheMetadataModuleReadsOrWritesTheDefinitionTablesOfApprovalProcesses() {
        noClasses().that().resideOutsideOfPackages("app.platform.metadata..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.metadata.internal.ApprovalProcessStore")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheApprovalModuleReadsOrWritesTheRuntimeTablesOfApprovals() {
        noClasses().that().resideOutsideOfPackages("app.platform.approval..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.approval.internal.ApprovalStore")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theLockContractIsImplementedByApprovalAndAskedByTheGuardAlone() {
        // Only the guard asks whether a record is locked; every write path asks the guard (ADR-0148).
        noClasses().that().resideInAPackage("app.platform.data..").and().haveSimpleNameNotEndingWith("RecordLockGuard")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sharedkernel.approval.RecordLocks")
                .because("a path that asks the lock itself could ask the wrong way round")
                .allowEmptyShould(true)
                .check(platform);
        classes().that().implement("app.platform.sharedkernel.approval.RecordLocks")
                .should().resideInAPackage("app.platform.approval.internal..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theApproverDirectoryIsAskedByTheApprovalModuleOnly() {
        noClasses().that().resideOutsideOfPackages("app.platform.approval..", "app.platform.security..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.security.ApproverDirectory")
                .because("who holds a profile or a role is not a question for any other module")
                .allowEmptyShould(true)
                .check(platform);
    }
}
