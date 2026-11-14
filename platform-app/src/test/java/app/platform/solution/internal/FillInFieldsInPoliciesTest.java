package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A field a policy does not list does not exist for the people it gives rights to (MC1.1, ADR-0259; MC2A, ADR-0260),
 * and a list that names a field the object lacks fails when a person reads. So every shipped policy that lists fields
 * of the fill-in lists the three the cluster copies onto it (the application, the plant and the month), and every one
 * that lists any of them lists them all. Read from the files that ship, with no database.
 */
class FillInFieldsInPoliciesTest {

    private static final String FILL = "InspectionFill";
    private static final Set<String> COPIED = Set.of("application", "plant", "period");

    @Test
    void everyShippedPolicyThatListsFieldsOfTheFillInListsTheApplicationThePlantAndTheMonth() {
        List<SolutionBundle> bundles = SolutionLoader.load("classpath*:solutions/inspection/solution.yml");
        assertThat(bundles).isNotEmpty();
        List<String> problems = new ArrayList<>();
        int judged = 0;
        for (SolutionBundle bundle : bundles) {
            for (SolutionBundle.Policy policy : bundle.policies()) {
                boolean lists = policy.fields().keySet().stream().anyMatch(key -> key.startsWith(FILL + "."));
                if (!lists) {
                    continue;
                }
                judged++;
                for (String field : COPIED) {
                    if (!policy.fields().containsKey(FILL + "." + field)) {
                        problems.add(policy.key() + " does not list " + field);
                    }
                }
            }
        }
        assertThat(judged).as("the policies that list fields of the fill-in").isGreaterThanOrEqualTo(4);
        assertThat(problems).isEmpty();
    }
}
