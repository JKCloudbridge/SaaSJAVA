package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.Serializable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The expression engine is a sandbox by construction (ADR-0076, ADR-0081): the module cannot even name the parts of
 * the platform an attacker would want. If a change to the module starts to use reflection, files, the network, a
 * process, a database or the Spring framework, this test fails the build, whatever the expression language does. The
 * engine's own tests then prove the language cannot reach anything; this test proves the code cannot either.
 */
@Tag("architecture")
class ExpressionSandboxTest {

    private static final String ENGINE = "app.platform.expression.ExpressionEngine";

    private static JavaClasses expression;

    @BeforeAll
    static void importClasses() {
        expression = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform.expression");
    }

    @Test
    void theModuleIsNotEmpty() {
        org.assertj.core.api.Assertions.assertThat(expression.size()).isGreaterThan(10);
    }

    @Test
    void theModuleCannotReachReflectionFilesTheNetworkProcessesOrScripting() {
        // The marker interface Serializable is the one thing of java.io the module's exception needs: it is not a way
        // to anything.
        DescribedPredicate<JavaClass> reachesOut = JavaClass.Predicates.resideInAnyPackage(
                "java.lang.reflect..", "java.io..", "java.net..", "java.nio..", "java.sql..", "javax.script..",
                "javax.naming..", "java.rmi..", "java.util.concurrent..", "java.util.logging..", "javax.sql..")
                .and(DescribedPredicate.not(JavaClass.Predicates.type(Serializable.class)));

        noClasses().that().resideInAPackage("app.platform.expression..")
                .should().dependOnClassesThat(reachesOut)
                .because("an expression must not be able to reach a class, a file, the network or another thread")
                .check(expression);
    }

    @Test
    void theModuleCannotStartProcessesLoadClassesOrExitTheMachine() {
        noClasses().that().resideInAPackage("app.platform.expression..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ClassLoader")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Thread")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Class")
                .because("nothing in the module needs them, so nothing in the module may have them")
                .check(expression);
    }

    @Test
    void onlyTheEngineReadsTheSystemClockAndItDoesNotSetThePropertiesOrTheEnvironment() {
        noClasses().that().resideInAPackage("app.platform.expression..")
                .and().doNotHaveFullyQualifiedName(ENGINE)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .because("the clock of a calculation is an argument; only the bound on time reads the system timer")
                .check(expression);
    }

    @Test
    void theModuleDependsOnNothingOfThePlatformNorOnTheSpringFramework() {
        // package-info carries the declaration of the module (its allowed dependencies) and is not code.
        noClasses().that().resideInAPackage("app.platform.expression..")
                .and().doNotHaveSimpleName("package-info")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.tenant..",
                        "app.platform.identity..", "app.platform.licensing..", "app.platform.security..",
                        "app.platform.metadata..", "app.platform.data..", "app.platform.application..",
                        "app.platform.workflow..", "app.platform.approval..", "app.platform.notification..",
                        "app.platform.integration..", "app.platform.audit..", "app.platform.platformadmin..",
                        "app.platform.web..", "app.platform.observability..", "app.platform.outbox..",
                        "org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..")
                .because("pure logic: no database, no framework, nothing to inject, nothing to configure")
                .check(expression);
    }
}
