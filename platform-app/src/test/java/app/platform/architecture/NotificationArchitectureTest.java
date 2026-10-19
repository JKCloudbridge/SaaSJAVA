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
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What keeps notices, templates and the failure view from becoming a second door or a new cycle (Sprint 25, ADR-0154
 * to ADR-0166). The module graph itself is verified by the module tests; these are the rules that graph cannot say.
 */
@Tag("architecture")
class NotificationArchitectureTest {

    private static final Path SOURCES = Path.of("src/main/java/app/platform");
    private static final String NOTICE_STORE = "app.platform.notification.internal.NoticeStore";

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    @Test
    void noModuleOutsideNotificationDependsOnItsInternals() {
        noClasses().that().resideOutsideOfPackages("app.platform.notification..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.notification.internal..")
                .because("other modules create notices through the shared-kernel contract Notices (ADR-0158)")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theBusinessModulesKnowNothingOfTheModuleThatDeliversNotices() {
        noClasses().that().resideInAnyPackage("app.platform.data..", "app.platform.workflow..",
                        "app.platform.approval..", "app.platform.platformadmin..", "app.platform.security..",
                        "app.platform.licensing..", "app.platform.metadata..")
                .should().dependOnClassesThat().resideInAPackage("app.platform.notification..")
                .because("a write path, an action or a job asks the contract, never the module that answers")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheNotificationModuleImplementsTheNoticeContract() {
        classes().that().implement("app.platform.sharedkernel.notice.Notices")
                .should().resideInAPackage("app.platform.notification.internal..")
                .because("every notice passes the one creator: preference, dedupe key and membership (ADR-0154)")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyOneClassCreatesNoticesAndNothingButTheNotificationModuleReadsThem() {
        noClasses().that().resideOutsideOfPackages("app.platform.notification..")
                .should().dependOnClassesThat().haveFullyQualifiedName(NOTICE_STORE)
                .allowEmptyShould(true)
                .check(platform);
        noClasses().that().resideInAPackage("app.platform.notification.internal..")
                .and().haveSimpleNameNotEndingWith("DefaultNotices")
                .and().haveSimpleNameNotEndingWith("NotificationService")
                .and().haveSimpleNameNotEndingWith("NoticeRetention")
                .and().haveSimpleNameNotEndingWith("NoticeStore")
                .and().haveSimpleNameNotEndingWith("MailHealth")
                .and().haveSimpleNameNotEndingWith("NoticeStore$Row")
                .should().dependOnClassesThat().haveFullyQualifiedName(NOTICE_STORE)
                .because("the creator, the member's own reads, the retention and the failure view are the doors")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void onlyTheMetadataModuleReadsOrWritesTheTextOfNotices() {
        noClasses().that().resideOutsideOfPackages("app.platform.metadata..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.metadata.internal.NotificationTemplateStore")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    void theAutomationHealthCollectorsAreTheTwoControllersAndTheSourcesAreBeans() {
        noClasses().that().resideOutsideOfPackages("app.platform.workflow..", "app.platform.platformadmin..",
                        "app.platform.approval..", "app.platform.notification..", "app.platform.sharedkernel..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "app.platform.sharedkernel.health.AutomationHealthSource")
                .allowEmptyShould(true)
                .check(platform);
    }

    // ---- source scans: the tables belong to their owners ----

    private static final Pattern NOTICE_TABLE = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+notification(\\s|$|\\))");
    private static final Pattern PREFERENCE_TABLE = Pattern.compile("(?i)\\bnotification_preference\\b");
    private static final Pattern TEMPLATE_TABLE = Pattern.compile("(?i)\\bnotification_template\\b");
    private static final Pattern REMINDER_TABLES = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+(approval_step_timer|approval_reminder)\\b");
    private static final Pattern TRIAL_MARKERS = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+subscription_notice\\b");

    private static List<Path> naming(Pattern pattern, String... owners) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        for (String owner : owners) {
                            if (normalized.contains("/app/platform/" + owner + "/")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(path -> {
                        try {
                            return pattern.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }
    }

    @Test
    void onlyNotificationNamesTheNoticeAndPreferenceTables() throws IOException {
        assertThat(naming(NOTICE_TABLE, "notification")).as("classes that name the notice table").isEmpty();
        assertThat(naming(PREFERENCE_TABLE, "notification")).as("classes that name the preference table").isEmpty();
    }

    @Test
    void onlyMetadataNamesTheTemplateTable() throws IOException {
        assertThat(naming(TEMPLATE_TABLE, "metadata")).as("classes that name the template table").isEmpty();
    }

    @Test
    void onlyApprovalNamesTheTimerTables() throws IOException {
        assertThat(naming(REMINDER_TABLES, "approval")).as("classes that name the timer tables").isEmpty();
    }

    @Test
    void onlyLicensingNamesTheTrialMarkers() throws IOException {
        assertThat(naming(TRIAL_MARKERS, "licensing")).as("classes that name the trial markers").isEmpty();
    }

    @Test
    void theScansFindTheirOwnersSoTheyCannotPassBecauseOfAWrongPath() throws IOException {
        assertThat(Files.readString(SOURCES.resolve("notification/internal/NoticeStore.java"))).containsPattern(
                NOTICE_TABLE);
        assertThat(naming(NOTICE_TABLE, "no-such-module")).isNotEmpty();
    }
}
