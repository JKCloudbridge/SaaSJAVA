package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
 * The seams of the template lifecycle (FA4, ADR-0188, ADR-0189): who may share a record from Java, who may move a
 * version of a sheet, who may answer "does this person hold an open task", who may write a record as the platform, and
 * that nothing depends on the cluster's module. Each rule is also held to a class that breaks it, so it is known to
 * fail when it should. */
@Tag("architecture")
class InspectionArchitectureTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    private static ArchRule onlyTheClusterMayShareFromJava() {
        return noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.data..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.RecordShares")
                .because("sharing a record from Java is an act a module has decided itself; the record store's own "
                        + "endpoints are for people (ADR-0188)")
                .allowEmptyShould(true);
    }

    private static ArchRule onlyTheClusterMayMoveAVersion() {
        return noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.SheetLifecycle")
                .because("the moves of a version are decided by the module that owns what the sheet belongs to "
                        + "(ADR-0188)")
                .allowEmptyShould(true);
    }

    @Test
    void onlyTheClusterSharesARecordFromJava() {
        onlyTheClusterMayShareFromJava().check(platform);
    }

    @Test
    void onlyTheClusterMovesAVersionOfASheet() {
        onlyTheClusterMayMoveAVersion().check(platform);
    }

    @Test
    void theRulesFailForAClassOutsideTheClusterThatDoesBoth() {
        JavaClasses sharer = new ClassFileImporter()
                .importClasses(app.platform.architecture.fixtures.ClusterLikeSharer.class);

        assertThat(onlyTheClusterMayShareFromJava().evaluate(sharer).hasViolation()).isTrue();
        assertThat(onlyTheClusterMayMoveAVersion().evaluate(sharer).hasViolation()).isTrue();
    }

    @Test
    void nothingDependsOnTheModuleOfTheCluster() {
        noClasses().that().resideOutsideOfPackage("app.platform.inspection..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.inspection..")
                .because("a solution's module is a leaf: the generic parts never know it (ADR-0180)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheApprovalModuleAnswersWhoHoldsAnOpenTask() {
        classes().that().implement("app.platform.sharedkernel.approval.ApprovalTasks")
                .should().resideInAPackage("app.platform.approval..")
                .because("a task is the approval module's; others ask the shared-kernel question (ADR-0189)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theClusterTakesThePlatformsDoorToRecordsOnlyToRetireATemplate() {
        noClasses().that().resideInAPackage("app.platform.inspection..")
                .and().haveSimpleNameNotContaining("TemplatePublishing")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.BackgroundWrites")
                .because("the one write the cluster makes as the platform is the retired mark, and it is made in one "
                        + "place (ADR-0188); the number is written through the engine's narrow writer")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theClusterNeverNamesTheTablesOfAnotherModule() {
        noClasses().that().resideInAPackage("app.platform.inspection..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.data.internal..",
                        "app.platform.sheets.internal..", "app.platform.security.internal..",
                        "app.platform.approval.internal..", "app.platform.metadata.internal..")
                .because("a module uses only another module's root package (the module rules, ADR-0001)")
                .allowEmptyShould(true).check(platform);
    }
}
