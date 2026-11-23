package app.platform.identity.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.access.ConnectedAppCaller;
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

/**
 * The seams of connected applications held by rules (Sprint 31, ADR-0267 to ADR-0272): only the identity module names
 * the tables of applications and secrets, nothing but the one store reads a hash and nothing but the one provider
 * compares a secret, the places that may read the value of a stored secret are the ones they were before this sprint,
 * the public API learns about its caller only through a contract of the shared kernel, and no endpoint can give a
 * secret back except the two that make one.
 */
@Tag("architecture")
class ClientsArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern TABLE_STATEMENT = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+(oauth_client|oauth_client_secret)\\b");
    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform", "app.platformapi");
    }

    private static List<Path> sourcesMatching(Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return pattern.matcher(Files.readString(path)).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    @Test
    void onlyTheClientStoreNamesTheTablesOfApplicationsAndSecrets() throws IOException {
        List<String> offenders = sourcesMatching(TABLE_STATEMENT).stream().map(path -> path.getFileName().toString())
                .filter(name -> !name.equals("ClientStore.java")).toList();

        assertThat(offenders).as("classes that name a table of applications or secrets in a statement").isEmpty();
        assertThat(Files.readString(MAIN.resolve("identity/internal/ClientStore.java")))
                .contains("from oauth_client c").contains("into oauth_client_secret");
    }

    @Test
    void onlyTheGrantStoreNamesTheColumnsThatTieAGrantToAnApplication() throws IOException {
        List<String> offenders = sourcesMatching(Pattern.compile("oauth_client_id|client_secret_id")).stream()
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.equals("AuthorizationStore.java")).toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void onlyTheClientStoreSpeaksOfTheHashColumn() throws IOException {
        List<String> offenders = sourcesMatching(Pattern.compile("secret_hash")).stream()
                .map(path -> path.getFileName().toString()).filter(name -> !name.equals("ClientStore.java")).toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void onlyTheClientAuthenticationProviderReadsAStoredHash() {
        noClasses().that().doNotHaveFullyQualifiedName(ClientAuthenticationProvider.class.getName())
                .and().doNotHaveFullyQualifiedName(ClientStore.class.getName())
                .and().doNotHaveFullyQualifiedName(ClientStore.SecretRow.class.getName())
                .should().callMethod(ClientStore.SecretRow.class, "hash")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheProviderComparesASecretAndOnlyTheRegistryMakesOne() {
        noClasses().that().doNotHaveFullyQualifiedName(ClientAuthenticationProvider.class.getName())
                .and().doNotHaveFullyQualifiedName(ClientSecrets.class.getName())
                .should().callMethod(ClientSecrets.class, "matches", String.class, String.class)
                .allowEmptyShould(true).check(platform);
        noClasses().that().doNotHaveFullyQualifiedName(ClientRegistry.class.getName())
                .and().doNotHaveFullyQualifiedName(ClientSecrets.class.getName())
                .should().callMethod(ClientSecrets.class, "generate")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void thePlacesThatMayReadTheValueOfAStoredSecretAreTheSameAsBeforeThisSprint() {
        // Connected applications keep only hashes and never touch the secrets store: this list did not grow.
        noClasses().that().doNotHaveFullyQualifiedName("app.platform.secrets.internal.DatabaseSecretsStore")
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.CredentialHeaders")
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.InboundSignatures")
                .and().doNotHaveFullyQualifiedName("app.platform.integration.internal.CredentialLogins")
                .and().resideOutsideOfPackage("app.platform.sharedkernel.secrets..")
                .should().callMethod(SecretValue.class, "reveal")
                .check(platform);
        noClasses().that().resideInAPackage("app.platform.identity..")
                .should().dependOnClassesThat().haveFullyQualifiedName(SecretValue.class.getName())
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theManagementOfApplicationsIsInternalAndNothingOutsideIdentityDependsOnIt() {
        noClasses().that().resideOutsideOfPackage("app.platform.identity..")
                .should().dependOnClassesThat().haveFullyQualifiedName(ClientRegistry.class.getName())
                .allowEmptyShould(true).check(platform);
        noClasses().that().resideOutsideOfPackage("app.platform.identity..")
                .should().dependOnClassesThat().haveFullyQualifiedName(ClientStore.class.getName())
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void thePublicApiLearnsAboutItsCallerOnlyThroughTheSharedKernelContract() {
        noClasses().that().resideInAPackage("app.platform.publicapi..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.identity..")
                .check(platform);
        classes().that().implement(ConnectedAppCaller.class).should().resideInAPackage("app.platform.identity..")
                .check(platform);
        noClasses().that().resideInAPackage("app.platform.publicapi..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.security.core.context.SecurityContextHolder")
                .check(platform);
    }

    @Test
    void anApplicationTokenIsHeldToThePublicApiByAFilterThatSitsRightAfterTheTokenCheck() throws IOException {
        String chain = Files.readString(MAIN.resolve("identity/internal/SecurityConfiguration.java"));
        int embedded = chain.indexOf("new EmbeddedAccessFilter(denied)");
        int client = chain.indexOf("new ClientTokenAccessFilter(denied)");
        int scopes = chain.indexOf("new PublicApiAccessFilter(denied)");

        assertThat(embedded).isPositive();
        assertThat(client).as("the filter for application tokens is in the chain").isGreaterThan(embedded);
        assertThat(scopes).as("scopes are judged after the path").isGreaterThan(client);
        assertThat(chain.indexOf("new UserContextFilter(contexts)")).as("and before the caller is made known")
                .isGreaterThan(client);
    }

    @Test
    void noEndpointGivesASecretBackExceptTheTwoThatMakeOne() {
        List<String> readers = platform.stream()
                .filter(type -> type.getName().equals(ConnectedAppController.class.getName()))
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping"))
                .map(method -> method.getName()).sorted().toList();
        List<String> returningASecret = platform.stream()
                .filter(type -> type.getName().equals(ConnectedAppController.class.getName()))
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.getReturnType().getAllInvolvedRawTypes().stream()
                        .anyMatch(type -> type.isEquivalentTo(app.platformapi.NewClientSecret.class)))
                .map(method -> method.getName()).sorted().toList();

        assertThat(readers).as("the GET endpoints").containsExactly("catalogue", "get", "list");
        assertThat(returningASecret).as("the only answers that carry a secret").containsExactly("register", "rotate");
    }

    @Test
    void noViewOfAnApplicationOrOfASecretHasAFieldThatCouldHoldAValue() {
        List<String> forbidden = List.of("value", "secret", "clientsecret", "plaintext", "hash", "secrethash",
                "password", "key", "token");
        classes().that().resideInAPackage("app.platformapi")
                .and().haveSimpleNameStartingWith("ConnectedApp").or().haveSimpleName("ClientSecretView")
                .should(new ArchCondition<JavaClass>("have no field that could hold a secret") {
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
    void onlyTheTwoAnswersThatMakeASecretHaveAFieldForOneAndItPrintsAsRedacted() {
        List<String> holders = platform.stream().filter(type -> type.getPackageName().equals("app.platformapi"))
                .filter(type -> type.getFields().stream().anyMatch(field ->
                        field.getName().toLowerCase(Locale.ROOT).contains("clientsecret")))
                .map(JavaClass::getSimpleName).sorted().toList();

        assertThat(holders).containsExactly("NewClientSecret");
        assertThat(new app.platformapi.NewClientSecret(null, "canary-secret").toString()).doesNotContain("canary");
    }

    @Test
    void theEndpointsOfApplicationsReturnTheEnvelopeTypeOrAResponseEntityOfIt() {
        methods().that().areDeclaredIn(ConnectedAppController.class).and().areAnnotatedWith(
                "org.springframework.web.bind.annotation.GetMapping")
                .should().haveRawReturnType(app.platformapi.ApiResponse.class)
                .check(platform);
    }
}
