package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import app.platform.sharedkernel.lifecycle.WorkerStop;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

/**
 * Every background worker stops through the one helper (Sprint 33, ADR-0289). Without it a worker either interrupts
 * the item in its hand at once, or waits its own number of seconds, and Spring stops the beans of a phase one after
 * the other, so a deployment's stop time would be the sum of them and no longer something that can be planned.
 *
 * <p>The rule is a fact about the code, not a hope: a new class that implements {@link SmartLifecycle} must answer
 * {@code stop(Runnable)} (so its stop runs beside the others) and must use {@link WorkerStop}.
 */
@Tag("architecture")
class WorkersStopOrderlyTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("app.platform");
    }

    @Test
    void everyLifecycleBeanStopsConcurrentlyThroughTheDrainHelper() {
        classes().that(areLifecycleBeans())
                .should(declareAsynchronousStop())
                .andShould(useTheDrainHelper())
                .because("a worker that stops by its own rule makes the stop time of an instance unplannable")
                .check(platform);
    }

    @Test
    void theRuleSeesTheWorkersWeKnowAbout() {
        long found = platform.stream().filter(candidate -> areLifecycleBeans().test(candidate)).count();
        // 18 workers and jobs existed when the rule was written, plus the shutdown delay. A count that falls means the
        // predicate stopped recognising them, which would make the rule above pass for the wrong reason.
        org.assertj.core.api.Assertions.assertThat(found).isGreaterThanOrEqualTo(19);
    }

    private static DescribedPredicate<JavaClass> areLifecycleBeans() {
        return DescribedPredicate.describe("implement SmartLifecycle", candidate ->
                !candidate.isInterface() && candidate.isAssignableTo(SmartLifecycle.class)
                        && !candidate.getName().startsWith("org.springframework"));
    }

    private static ArchCondition<JavaClass> declareAsynchronousStop() {
        return new ArchCondition<>("declare stop(Runnable)") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                boolean declared = candidate.getMethods().stream().anyMatch(method -> method.getName().equals("stop")
                        && method.getRawParameterTypes().size() == 1
                        && method.getRawParameterTypes().get(0).isEquivalentTo(Runnable.class));
                if (!declared) {
                    events.add(SimpleConditionEvent.violated(candidate,
                            candidate.getName() + " does not declare stop(Runnable)"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> useTheDrainHelper() {
        return new ArchCondition<>("use WorkerStop") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                boolean uses = candidate.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(WorkerStop.class));
                if (!uses) {
                    events.add(SimpleConditionEvent.violated(candidate,
                            candidate.getName() + " does not use WorkerStop"));
                }
            }
        };
    }
}
