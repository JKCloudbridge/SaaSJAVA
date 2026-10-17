package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What keeps workflows from becoming a second path to the platform (Sprint 22, ADR-0133 to ADR-0137). The module graph
 * itself is verified by the module tests; these are the rules that graph cannot say.
 */
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
                .because("a workflow reads through BackgroundRecords; changing records is a later action type that "
                        + "goes through Records and is reviewed on its own")
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
