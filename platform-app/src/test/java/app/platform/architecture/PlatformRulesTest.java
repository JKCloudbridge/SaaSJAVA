package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import app.platformapi.ApiEnvelope;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cross-cutting rules from the delivery plan's "standing architecture rules" and the coding standards. */
@Tag("architecture")
class PlatformRulesTest {

    private static final List<Class<? extends Annotation>> ENDPOINT_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class, PutMapping.class, PatchMapping.class,
            DeleteMapping.class);

    private static final String[] BUSINESS_MODULES = {
        "app.platform.tenant..", "app.platform.identity..", "app.platform.licensing..",
        "app.platform.security..", "app.platform.metadata..", "app.platform.data..",
        "app.platform.application..", "app.platform.workflow..", "app.platform.approval..",
        "app.platform.notification..", "app.platform.integration..", "app.platform.audit..",
        "app.platform.platformadmin.."
    };

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
                .resideInAnyPackage(BUSINESS_MODULES)
                .check(platform);
        noClasses().that().resideInAPackage("app.platform.sharedkernel..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("app.platform.web..", "app.platform.observability..")
                .check(platform);
    }

    @Test
    void businessModulesDoNotDependOnTheInfrastructureWebModule() {
        // Controllers use the API contract and throw ApiException; only the web module turns those into responses.
        noClasses().that().resideInAnyPackage(BUSINESS_MODULES)
                .should().dependOnClassesThat().resideInAPackage("app.platform.web..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void businessModulesUseTheEventContractsOfTheSharedKernelNotTheOutboxModule() {
        // They announce events through EventPublisher and react through EventHandler beans (ADR-0016), so no module
        // needs, or may have, an edge to the outbox.
        noClasses().that().resideInAnyPackage(BUSINESS_MODULES)
                .should().dependOnClassesThat().resideInAPackage("app.platform.outbox..")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheTenantAndOutboxModulesEnterASystemScope() {
        // Working across tenants is the one deliberate exception to tenant isolation (ADR-0015); it must stay
        // visible in one or two places, never spread through the business modules.
        noClasses().that().resideOutsideOfPackages("app.platform.tenant..", "app.platform.outbox..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.tenant.SystemScope")
                .because("system scopes are platform infrastructure")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theTenantModuleDoesNotReachIntoTheOutboxOrAnyBusinessModule() {
        noClasses().that().resideInAPackage("app.platform.tenant..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.outbox..", "app.platform.web..")
                .check(platform);
    }

    @Test
    void controllerEndpointsReturnTheApiEnvelopeOrAResponseEntity() {
        // Clients can rely on {"data": ...}, {"data": [...], "pagination": ...} and {"error": ...} everywhere.
        DescribedPredicate<JavaMethod> endpoints = DescribedPredicate.describe(
                "are REST endpoints",
                method -> method.getOwner().isAnnotatedWith(RestController.class)
                        && ENDPOINT_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith));
        DescribedPredicate<JavaClass> envelopeLike = JavaClass.Predicates.assignableTo(ApiEnvelope.class)
                .or(JavaClass.Predicates.assignableTo(ResponseEntity.class))
                .or(DescribedPredicate.describe("void", type -> type.isEquivalentTo(void.class)));

        methods().that(endpoints)
                .should().haveRawReturnType(envelopeLike)
                .because("every body is wrapped in the envelope of the API contract")
                .allowEmptyShould(false)
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
