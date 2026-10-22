package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Keeps a shipped solution's words out of the generic platform (Functional track FA1, ADR-0180, ADR-0181). The rules:
 * the cluster's own words (its key, its objects, its label) appear only where the cluster lives; the generic modules
 * speak of solutions, sheets, versions and fills; the module that loads solutions knows nothing of any one of them; and
 * the word "audit" keeps meaning the audit trail: a class, table, endpoint or ability whose name has it is one of the
 * trail's, listed below, and a new one fails the build until a person decides it is.
 *
 * <p>The places a cluster may live are listed here and nowhere else. Adding a place is a decision a reviewer sees.
 */
@Tag("architecture")
class SolutionBoundaryTest {

    private static final Path ROOT = Path.of("..");
    private static final Path APP = ROOT.resolve("platform-app/src/main");

    /** Forms of the cluster's words: its key, its objects and the old working names ADR-0180 replaced. */
    private static final List<Pattern> CLUSTER_WORDS = List.of(
            Pattern.compile("\\binspection\\b"),
            Pattern.compile("\\bInspection(Template|Fill)\\b"),
            Pattern.compile("\\bTemplateChange(Request|Comment)\\b"),
            Pattern.compile("app\\.platform\\.inspection\\b"),
            Pattern.compile("(?i)audit[-_ ]?maintenance"));

    /** Where the cluster's words may be written (paths relative to the repository root, with forward slashes). */
    private static final List<String> CLUSTER_PLACES = List.of(
            "platform-app/src/main/resources/solutions/inspection/",
            "platform-app/src/main/resources/db/migration/V058__add_inspection_feature.sql",
            "platform-app/src/main/resources/db/migration/V066__create_inspection_publication.sql",
            "platform-web/src/solutions/",
            "platform-app/src/main/java/app/platform/inspection/");

    /** The classes of the audit trail's users outside module audit, as they are today (frozen). */
    private static final Set<String> AUDIT_TRAIL_USERS = Set.of("ApprovalAudit.java", "AuthAudit.java",
            "MetadataAudit.java", "PlatformAudit.java", "PlatformAuditController.java", "AccessAudit.java",
            "AuditEventView.java");

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    // ---- the cluster's words ----

    @Test
    void noGenericCodeResourceOrScreenNamesTheCluster() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : genericFiles()) {
            String text = Files.readString(file);
            for (Pattern word : CLUSTER_WORDS) {
                Matcher match = word.matcher(text);
                if (match.find()) {
                    found.add(relative(file) + " names the cluster (" + word.pattern() + ")");
                }
            }
        }

        assertThat(found).as("generic code speaks of solutions, sheets, versions and fills (ADR-0180); the cluster's "
                + "words live only in " + CLUSTER_PLACES).isEmpty();
    }

    @Test
    void theScanSeesTheFilesItIsMeantToSee() throws IOException {
        List<String> seen = genericFiles().stream().map(SolutionBoundaryTest::relative).toList();

        assertThat(seen).contains("platform-app/src/main/java/app/platform/metadata/internal/MetadataCatalogue.java",
                "platform-app/src/main/java/app/platform/solution/internal/SolutionLoader.java",
                "platform-app/src/main/resources/application.yml",
                "platform-web/src/components/apps/ApplicationEntryView.tsx");
        assertThat(seen).as("the places a cluster may live are not scanned").noneMatch(path -> allowed(path));
    }

    @Test
    void theScanCanFail() {
        assertThat(CLUSTER_WORDS).anyMatch(word -> word.matcher("new InspectionTemplate()").find());
        assertThat(CLUSTER_WORDS).anyMatch(word -> word.matcher("\"inspection\"").find());
        assertThat(CLUSTER_WORDS).anyMatch(word -> word.matcher("Audit maintenance").find());
        assertThat(CLUSTER_WORDS).noneMatch(word -> word.matcher("Inspection__c").find());
    }

    @Test
    void theModuleThatLoadsSolutionsUsesNoWordOfTemplating() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(APP.resolve("java/app/platform/solution"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Pattern.compile("(?i)template").matcher(Files.readString(file)).find()) {
                    found.add(relative(file));
                }
            }
        }

        assertThat(found).as("the generic modules say sheet, version and fill, never template (ADR-0180)").isEmpty();
    }

    // ---- the word audit ----

    @Test
    void theWordAuditNamesOnlyTheAuditTrail() throws IOException {
        List<String> found = new ArrayList<>();
        for (String module : List.of("platform-app", "platform-shared-kernel", "platform-api-contract")) {
            Path java = ROOT.resolve(module).resolve("src/main/java");
            try (Stream<Path> files = Files.walk(java)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String path = file.toString().replace('\\', '/');
                    String name = file.getFileName().toString();
                    boolean trail = path.contains("/app/platform/audit/") || path.contains("/sharedkernel/audit/");
                    if (name.toLowerCase().contains("audit") && !trail && !AUDIT_TRAIL_USERS.contains(name)) {
                        found.add("class " + name);
                    }
                }
            }
        }
        for (Path migration : migrations()) {
            Matcher tables = Pattern.compile("(?im)^\\s*create\\s+table\\s+([a-z0-9_]+)").matcher(
                    Files.readString(migration));
            while (tables.find()) {
                if (tables.group(1).contains("audit") && !tables.group(1).equals("audit_record")) {
                    found.add("table " + tables.group(1));
                }
            }
        }
        for (String path : quoted(ROOT.resolve("platform-api-contract/src/main/java/app/platformapi/ApiPaths.java"))) {
            if (path.toLowerCase().contains("audit") && !path.endsWith("/audit-events")) {
                found.add("endpoint " + path);
            }
        }
        for (String key : quoted(APP.resolve("java/app/platform/security/Ability.java"))) {
            boolean looksLikeAKey = key.matches("[a-z][a-z0-9.-]*");
            if (looksLikeAKey && key.contains("audit") && !key.equals("audit.view")) {
                found.add("ability " + key);
            }
        }

        assertThat(found).as("\"audit\" means the audit trail; a new name that has it must be one of the trail's and "
                + "be added to this test by a person (ADR-0180)").isEmpty();
    }

    // ---- the module graph ----

    @Test
    void theMenuModuleHasNoEdgeToTheModuleThatLoadsSolutions() {
        noClasses().that().resideInAPackage("app.platform.application..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.solution..")
                .because("the menu asks the shared-kernel contract ShippedApplications through an optional bean "
                        + "(ADR-0175)")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void theModuleThatLoadsSolutionsDependsOnNoBusinessModuleItDoesNotDeclare() {
        noClasses().that().resideInAPackage("app.platform.solution..")
                .should().dependOnClassesThat().resideInAnyPackage("app.platform.application..",
                        "app.platform.data..", "app.platform.workflow..", "app.platform.approval..",
                        "app.platform.notification..", "app.platform.platformadmin..", "app.platform.identity..",
                        "app.platform.web..")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void noGenericModuleDependsOnTheModuleThatLoadsSolutions() {
        noClasses().that().resideOutsideOfPackages("app.platform.solution..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.solution..")
                .because("a solution is reached through the shared-kernel contracts, never by its module")
                .allowEmptyShould(true).check(platform);
    }

    @Test
    void onlyTheSolutionModuleAnswersTheContractsOfShippedSolutions() {
        classes().that().implement("app.platform.sharedkernel.solution.ShippedApplications")
                .or().implement("app.platform.sharedkernel.solution.FeatureNotes")
                .or().implement("app.platform.sharedkernel.solution.NumberSeriesDeclarations")
                .should().resideInAPackage("app.platform.solution..").allowEmptyShould(true).check(platform);
    }

    // ---- scanning ----

    private static boolean allowed(String relative) {
        return CLUSTER_PLACES.stream().anyMatch(relative::startsWith);
    }

    /** Every generic main file: code and resources of the backend and the non-test sources of the web application. */
    private static List<Path> genericFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("platform-app/src/main", "platform-api-contract/src/main",
                "platform-shared-kernel/src/main", "platform-web/src")) {
            try (Stream<Path> walk = Files.walk(ROOT.resolve(root))) {
                walk.filter(Files::isRegularFile).filter(SolutionBoundaryTest::scanned)
                        .filter(path -> !allowed(relative(path))).forEach(files::add);
            }
        }
        return files;
    }

    private static boolean scanned(Path path) {
        String name = path.getFileName().toString();
        String relative = relative(path);
        boolean text = Stream.of(".java", ".kt", ".sql", ".yml", ".yaml", ".json", ".ts", ".tsx", ".css", ".md",
                ".properties", ".xml").anyMatch(name::endsWith);
        boolean test = name.endsWith(".test.ts") || name.endsWith(".test.tsx") || relative.contains("/src/test/");
        boolean generated = relative.contains("/generated/") || relative.contains("/openapi/");
        return text && !test && !generated;
    }

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(APP.resolve("resources/db/migration"))) {
            return files.filter(path -> path.toString().endsWith(".sql")).toList();
        }
    }

    private static List<String> quoted(Path file) throws IOException {
        List<String> values = new ArrayList<>();
        Matcher literal = Pattern.compile("\"([^\"\\n]*)\"").matcher(Files.readString(file));
        while (literal.find()) {
            values.add(literal.group(1));
        }
        return values;
    }

    private static String relative(Path path) {
        return ROOT.relativize(path).normalize().toString().replace('\\', '/');
    }
}
