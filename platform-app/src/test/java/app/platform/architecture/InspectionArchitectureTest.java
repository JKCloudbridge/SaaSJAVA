package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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

    private static ArchRule onlyTheClusterMayMoveAFillIn() {
        return noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.FillLifecycle")
                .because("the moves of a fill-in are decided by the module that owns its header record (ADR-0192)")
                .allowEmptyShould(true);
    }

    @Test
    void onlyTheClusterSendsAFillInWithoutAReview() {
        noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.FillSubmission")
                .because("the module that owns the header decides when a fill-in is sent for good (ADR-0196)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheClusterAsksTheFillInReport() {
        noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.FillReports")
                .because("who may see a fill-in is decided by the module that owns the header record (ADR-0193)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheClusterGivesASheetAsAFile() {
        noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.SheetExports")
                .because("whether a file may be given at all is decided by the module that owns what the sheet "
                        + "belongs to (ADR-0201)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheClusterDrawsAPageOfCellsAsASheetOrAFile() {
        noClasses().that().resideOutsideOfPackages("app.platform.inspection..", "app.platform.sheets..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.sheets.SheetPages")
                .because("which cell of a page holds what is the business of the module that owns the form (ADR-0202)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheApprovalModuleAnswersWhatWasDecidedOnARequest() {
        classes().that().implement("app.platform.sharedkernel.approval.ApprovalDecisions")
                .should().resideInAPackage("app.platform.approval..")
                .because("the history of a request is the approval module's; others read it through the shared-kernel "
                        + "contract (ADR-0202)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheClusterDecidesWhetherAMemberMayStartADraftOfItsOwners() {
        classes().that().implement("app.platform.sharedkernel.solution.SheetDraftRules")
                .should().resideInAPackage("app.platform.inspection..")
                .because("the generic sheets module asks the rules of the owner's module and knows none (ADR-0202)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheClusterMovesAFillIn() {
        onlyTheClusterMayMoveAFillIn().check(platform);
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
        assertThat(onlyTheClusterMayMoveAFillIn().evaluate(sharer).hasViolation()).isTrue();
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
    void theClusterTakesThePlatformsDoorToRecordsInThreePlacesOnly() {
        noClasses().that().resideInAPackage("app.platform.inspection..")
                .and().haveSimpleNameNotContaining("TemplatePublishing")
                .and().haveSimpleNameNotContaining("FillHeaders")
                .and().haveSimpleNameNotContaining("ChangeRequestHeaders")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.BackgroundWrites")
                .because("the writes the cluster makes as the platform are the retired mark of a template "
                        + "(ADR-0188), the header of a fill-in with its status (ADR-0192) and the review record of "
                        + "a change request with its status (ADR-0199), each made in one place; "
                        + "the number is written through the engine's narrow writer")
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

    // ---- the objects an application of the kind uses are data (FA13B, ADR-0226) ----

    /** A text that is the API name of one of the objects the shipped applications of the kind declare. */
    private static final Pattern A_DECLARED_OBJECT = Pattern.compile(
            "\"(Inspection|Environment|Management)(Template|Fill|ChangeRequest)\"");

    @Test
    void noClassOfTheClusterNamesTheObjectOfAnApplicationAsAConstant() throws IOException {
        Path cluster = Path.of("..", "platform-app", "src", "main", "java", "app", "platform", "inspection");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(cluster)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (A_DECLARED_OBJECT.matcher(Files.readString(file)).find()) {
                    found.add(file.getFileName().toString());
                }
            }
        }

        assertThat(found).as("the cluster asks RecordSets which objects an application means (ADR-0226); the "
                + "objects are named in the solution files only").isEmpty();
    }

    @Test
    void theScanForAnObjectNameCanFail() {
        assertThat(A_DECLARED_OBJECT.matcher("static final String OBJECT = \"InspectionTemplate\";").find()).isTrue();
        assertThat(A_DECLARED_OBJECT.matcher("\"EnvironmentFill\"").find()).isTrue();
        assertThat(A_DECLARED_OBJECT.matcher("\"DepartmentGroup\"").find()).isFalse();
    }

    @Test
    void noClassOutsideTheSolutionModuleReadsTheDeclarationsOfTheParts() {
        classes().that().implement("app.platform.sharedkernel.solution.ObjectRoleDeclarations")
                .should().resideInAPackage("app.platform.solution..")
                .because("the parts of an application are read from the solution files by the module that loads them "
                        + "(ADR-0226)")
                .allowEmptyShould(true).check(platform);
    }
}
