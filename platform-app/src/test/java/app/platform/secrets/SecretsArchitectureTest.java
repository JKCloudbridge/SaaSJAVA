package app.platform.secrets;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.secrets.SecretValue;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The leak guarantee held by rules (Sprint 27, ADR-0214): what may touch a secret is the store, the cipher and the one
 * place that builds the header at the moment of use; nothing else depends on the implementation, names its tables,
 * prints a value, or has an endpoint that could give one back.
 */
@Tag("architecture")
class SecretsArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern TABLE_STATEMENT = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+(secret_entry|secret_version|secret_usage)\\b");
    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    @Test
    void nothingOutsideTheSecretsModuleDependsOnItsImplementation() {
        noClasses().that().resideOutsideOfPackage("app.platform.secrets..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.secrets..")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theSecretsModuleDoesNotDependOnTheIntegrationModuleOrTheDataEngine() {
        noClasses().that().resideInAPackage("app.platform.secrets..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.integration..",
                        "app.platform.data..", "app.platform.metadata..", "app.platform.workflow..")
                .check(platform);
    }

    @Test
    void onlyTheStoreAndTheTwoCallersAtTheMomentOfUseReadTheValueOfASecret() {
        // Sprint 28 (ADR-0221, ADR-0223): the header of an outgoing call (the code that ConnectionTesting held in
        // Sprint 27, now shared with the sender) and the check of the signature of an incoming one.
        noClasses().that().doNotHaveFullyQualifiedName("app.platform.secrets.internal.DatabaseSecretsStore")
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.CredentialHeaders")
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.InboundSignatures")
                // Sprint 29A (ADR-0239): the sign-in of a file transfer connection, the sibling of the header builder.
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.CredentialLogins")
                .and().resideOutsideOfPackage("app.platform.sharedkernel.secrets..")
                .should().callMethod(SecretValue.class, "reveal")
                .check(platform);
    }

    @Test
    void onlyTheStoreNamesTheSecretTablesAndOnlyTheTablesClassWritesStatements() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("SecretTables.java"))
                    .filter(path -> {
                        try {
                            return TABLE_STATEMENT.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }

        assertThat(offenders).as("classes that name a secret table in a statement").isEmpty();
        assertThat(Files.readString(MAIN.resolve("secrets/internal/SecretTables.java")))
                .contains("from secret_entry").contains("into secret_version");
    }

    @Test
    void aSecretValueCannotBeTurnedIntoJson() {
        SecretValue value = SecretValue.of("canary-value-in-json");
        JsonMapper mapper = JsonMapper.builder().build();

        String written;
        try {
            written = mapper.writeValueAsString(value);
        } catch (RuntimeException refused) {
            written = "refused";
        }

        assertThat(written).doesNotContain("canary");
        assertThat(value.toString()).doesNotContain("canary");
        assertThat(String.valueOf(value)).isEqualTo("SecretValue[REDACTED]");
    }

    @Test
    void theRequestToWriteACredentialPrintsAsRedacted() {
        assertThat(new app.platformapi.WriteCredentialRequest("canary-value").toString()).doesNotContain("canary");
    }

    @Test
    void noViewOfACredentialHasAFieldThatCouldHoldAValue() {
        List<String> forbidden = List.of("value", "secret", "plaintext", "ciphertext", "password", "key", "token");
        classes().that().resideInAPackage("app.platformapi").and().haveSimpleName("CredentialView")
                .should(new ArchCondition<JavaClass>("have no field that could hold a value") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        type.getFields().forEach(field -> {
                            if (forbidden.contains(field.getName().toLowerCase(Locale.ROOT))) {
                                events.add(SimpleConditionEvent.violated(field,
                                        type.getName() + " has the field " + field.getName()));
                            }
                        });
                    }
                }).check(platform);
    }

    @Test
    void theCredentialEndpointsGiveNothingBackButFactsAndHaveNoWayToReadAValue() {
        List<String> readers = platform.stream()
                .filter(type -> type.getPackageName().equals("app.platform.secrets.internal"))
                .filter(type -> type.getSimpleName().equals("CredentialController"))
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping"))
                .map(method -> method.getName()).toList();

        assertThat(readers).as("the only GET of the credential endpoints").containsExactly("list");
        noClasses().that().haveSimpleName("CredentialController")
                .should().dependOnClassesThat().haveFullyQualifiedName(SecretValue.class.getName())
                .allowEmptyShould(true).check(platform);
    }
}
