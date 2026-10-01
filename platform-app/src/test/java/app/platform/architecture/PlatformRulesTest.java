package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cross-cutting rules from the delivery plan's "standing architecture rules" and the coding standards. */
@Tag("architecture")
class PlatformRulesTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    @Test
    void coreModulesNeverReferenceExternalSystemAdapters() {
        // Only the integration module knows adapters.
        noClasses().that().resideInAPackage("app.platform..")
                .and().resideOutsideOfPackage("app.platform.integration..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.integration.adapter..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void sharedKernelDependsOnNoOtherModule() {
        noClasses().that().resideInAPackage("app.platform.sharedkernel..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "app.platform.tenant..", "app.platform.identity..", "app.platform.licensing..",
                        "app.platform.security..", "app.platform.metadata..", "app.platform.data..",
                        "app.platform.application..", "app.platform.workflow..", "app.platform.approval..",
                        "app.platform.notification..", "app.platform.integration..", "app.platform.audit..",
                        "app.platform.platformadmin..")
                .check(platform);
    }

    @Test
    void apiContractDependsOnNothingInThePlatform() {
        noClasses().that().resideInAPackage("app.platformapi..")
                .should().dependOnClassesThat().resideInAPackage("app.platform..")
                .check(platform);
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("app.platform.(*)..").should().beFreeOfCycles().check(platform);
    }

    @Test
    void noFieldInjection() {
        fields().should().notBeAnnotatedWith(Autowired.class)
                .because("use constructor injection")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void productionCodeDoesNotWriteToStandardStreams() {
        NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(platform);
    }
}
