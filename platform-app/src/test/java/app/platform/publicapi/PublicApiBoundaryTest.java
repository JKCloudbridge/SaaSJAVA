package app.platform.publicapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The public API is a new door on the same house and nothing else (Sprint 30, ADR-0244 to ADR-0246), held by rules:
 * nothing depends on it, it reaches data only through the contracts of the record store, it writes no statement and
 * names no table, no internal controller is mapped under its prefix, and every operation of it is named in the
 * contract.
 */
@Tag("architecture")
class PublicApiBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern SQL = Pattern.compile(
            "(?i)\\b(select\\s+.+\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from|object_record|"
                    + "idempotency_record|jdbc)\\b");
    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    @Test
    void nothingDependsOnThePublicApi() {
        noClasses().that().resideOutsideOfPackage("app.platform.publicapi..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.publicapi..")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void thePublicApiReachesDataOnlyThroughTheContractsOfTheRecordStoreAndAsksNothingElseOfThePlatform() {
        classes().that().resideInAPackage("app.platform.publicapi..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "jakarta..", "javax.annotation..", "org.springframework..", "org.springdoc..",
                        "io.swagger..",
                        "io.micrometer..", "tools.jackson..", "com.fasterxml.jackson.annotation..", "org.slf4j..",
                        "app.platformapi..",
                        "app.platform.publicapi..",
                        // The record store's contracts: its root package, never its internals.
                        "app.platform.data", "app.platform.tenant", "app.platform.sharedkernel..",
                        "app.platform.observability")
                .check(platform);
    }

    @Test
    void thePublicApiNeverNamesADatabaseTypeOfTheRecordStoreOrOfAnyOtherModule() {
        noClasses().that().resideInAPackage("app.platform.publicapi..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.data.internal..",
                        "app.platform.metadata..", "app.platform.security..", "app.platform.identity..",
                        "app.platform.workflow..", "app.platform.approval..", "app.platform.integration..",
                        "app.platform.secrets..", "app.platform.sheets..", "app.platform.inspection..",
                        "app.platform.solution..", "org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(platform);
    }

    @Test
    void noSourceOfThePublicApiWritesAStatementOrNamesATable() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN.resolve("publicapi"))) {
            offenders = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return SQL.matcher(stripComments(Files.readString(path))).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    void everyOperationOfThePublicApiIsNamedForTheContractAndLivesInTheInternalPackage() {
        for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(GetMapping.class, PostMapping.class,
                PatchMapping.class, DeleteMapping.class, PutMapping.class, RequestMapping.class)) {
            methods().that().areAnnotatedWith(mapping).and().areDeclaredInClassesThat()
                    .resideInAPackage("app.platform.publicapi..")
                    .should().beAnnotatedWith(Operation.class).allowEmptyShould(true).check(platform);
        }
        classes().that().resideInAPackage("app.platform.publicapi..").and().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("app.platform.publicapi.internal").andShould().notBePublic()
                .check(platform);
    }

    @Test
    void noControllerOutsideThePublicApiIsMappedUnderItsPrefix() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/publicapi/"))
                    .filter(path -> {
                        try {
                            String source = stripComments(Files.readString(path));
                            return source.contains("@RestController") && (source.contains("PUBLIC_V1")
                                    || source.contains("PUBLIC_OBJECTS") || source.contains("/api/public"));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }

        assertThat(offenders).as("an internal controller mapped under the public prefix").isEmpty();
    }

    @Test
    void theWritesOfThePublicApiAllGoThroughTheContractsThatTakeTheKeyAndTheGate() {
        // The only classes that may name the contracts that write records are the controllers of the module.
        noClasses().that().resideInAPackage("app.platform.publicapi..")
                .and().doNotHaveSimpleName("PublicRecordsController").and().doNotHaveSimpleName("PublicBulkController")
                .and().doNotHaveSimpleName("PublicQueryController")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.platform.data.Records")
                .allowEmptyShould(true).check(platform);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
