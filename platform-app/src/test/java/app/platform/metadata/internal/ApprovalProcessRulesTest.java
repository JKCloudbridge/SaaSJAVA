package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platformapi.ApiException;
import app.platformapi.ApprovalApproverSpec;
import app.platformapi.ApprovalFieldSpec;
import app.platformapi.ApprovalOutcomesSpec;
import app.platformapi.ApprovalStepSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What can be checked of an approval process from its request alone (ADR-0145, ADR-0146, ADR-0149), without a database:
 * the
 * names and their bounds, the grammar of the entry criteria, the closed kinds of approver rule with the one value each
 * takes,
 * the completion rules, the fallback, and the outcomes. Every problem is kept under its place and none repeats text a
 * person
 * typed.
 */
class ApprovalProcessRulesTest {

    private static final MetadataProperties PROPERTIES = new MetadataProperties(
            new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50, 200,
                    1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 3, 2, 4),
            new MetadataProperties.Cache(true, 1000));
    private static final ApprovalProcessRules RULES = new ApprovalProcessRules(
            new ExpressionSupport(new ExpressionEngine(Limits.standard()), PROPERTIES), PROPERTIES);

    private static ApprovalApproverSpec user() {
        return new ApprovalApproverSpec("USER", UUID.randomUUID().toString());
    }

    private static ApprovalStepSpec step(String name, String completion, ApprovalApproverSpec... approvers) {
        return new ApprovalStepSpec(name, "Label " + name, completion, List.of(approvers));
    }

    private static Map<String, List<String>> problems(String criteria, List<ApprovalStepSpec> steps,
            ApprovalOutcomesSpec outcomes, ApprovalApproverSpec fallback) {
        Problems found = new Problems();
        RULES.check(found, "Label", "", criteria, false, steps, outcomes, fallback);
        try {
            found.throwIfAny();
            return Map.of();
        } catch (ApiException refused) {
            return refused.fields();
        }
    }

    @Test
    void aGoodProcessHasNoProblem() {
        assertThat(problems("amount__c > 10", List.of(step("Manager", "ANY", user()), step("Finance", "ALL", user())),
                new ApprovalOutcomesSpec(List.of(new ApprovalFieldSpec("status__c", "Approved")), List.of(), List.of()),
                user())).isEmpty();
    }

    @Test
    void aProcessNeedsAtLeastOneStepAndNoMoreThanTheLimit() {
        assertThat(problems(null, List.of(), null, null)).containsKey("steps");

        Map<String, List<String>> tooMany = problems(null, List.of(step("A", "ANY", user()), step("B", "ANY", user()),
                step("C", "ANY", user()), step("D", "ANY", user())), null, null);
        assertThat(tooMany).containsKey("steps");
        assertThat(tooMany.get("steps")).singleElement().asString().contains("3");
    }

    @Test
    void aStepNeedsANameOnceALabelAKnownCompletionAndSomebodyToAsk() {
        Map<String, List<String>> found = problems(null, List.of(
                new ApprovalStepSpec("1 bad name", "", "SOMETIMES", List.of()),
                step("Same", "ANY", user()), step("same", "ANY", user())), null, null);

        assertThat(found).containsKeys("steps[0].name", "steps[0].label", "steps[0].completion", "steps[0].approvers",
                "steps[2].name");
    }

    @Test
    void aRuleOfAKindThatTakesAnIdentifierNeedsOneAndOneThatTakesNoneTakesNone() {
        // Two steps, not one: the shared properties cap a step at 2 rules for who is asked.
        Map<String, List<String>> found = problems(null, List.of(
                step("A", "ANY", new ApprovalApproverSpec("USER", "not-an-identifier"),
                        new ApprovalApproverSpec("OWNER", "extra")),
                step("B", "ANY", new ApprovalApproverSpec("RECORD_USER", "1bad"),
                        new ApprovalApproverSpec("NOBODY", null))),
                null, null);

        assertThat(found).containsKeys("steps[0].approvers[0].value", "steps[0].approvers[1].value",
                "steps[1].approvers[0].value", "steps[1].approvers[1].kind");
    }

    @Test
    void aRuleGivenTwiceAndMoreRulesThanTheLimitAreRefused() {
        ApprovalApproverSpec owner = new ApprovalApproverSpec("OWNER", null);
        ApprovalApproverSpec user = user();

        assertThat(problems(null, List.of(step("A", "ANY", owner, owner)), null, null))
                .containsKey("steps[0].approvers[1]");
        assertThat(problems(null, List.of(step("A", "ANY", owner, user, user())), null, null))
                .containsKey("steps[0].approvers");
    }

    @Test
    void theKindsThatNeedNoValueAreCleanedOfOne() {
        Problems found = new Problems();
        RULES.check(found, "Label", "", null, null, List.of(step("A", "any",
                new ApprovalApproverSpec("owner_role_manager", null))), null, null);

        assertThat(found.any()).isFalse();
    }

    @Test
    void entryCriteriaThatDoNotFollowTheGrammarAreRefusedAtTheirPlace() {
        assertThat(problems("amount__c >", List.of(step("A", "ANY", user())), null, null)).containsKey("entryCriteria");
    }

    @Test
    void theFallbackIsAPersonOrAGroupAndNothingElse() {
        ApprovalApproverSpec group = new ApprovalApproverSpec("GROUP", UUID.randomUUID().toString());

        assertThat(problems(null, List.of(step("A", "ANY", user())), null, group)).isEmpty();
        assertThat(problems(null, List.of(step("A", "ANY", user())), null, new ApprovalApproverSpec("OWNER", null)))
                .containsKey("fallback.kind");
    }

    @Test
    void anOutcomeSetsAFewFieldsOnceEachToANonEmptyLiteralOfASaneLength() {
        List<ApprovalFieldSpec> bad = new ArrayList<>(List.of(new ApprovalFieldSpec("status__c", "a"),
                new ApprovalFieldSpec("status__c", "b"), new ApprovalFieldSpec("1bad", "c"),
                new ApprovalFieldSpec("other__c", "")));
        Map<String, List<String>> found = problems(null, List.of(step("A", "ANY", user())),
                new ApprovalOutcomesSpec(List.of(), bad, List.of()), null);

        assertThat(found).containsKeys("outcomes.rejected[1].field", "outcomes.rejected[2].field",
                "outcomes.rejected[3].value");
        assertThat(problems(null, List.of(step("A", "ANY", user())), new ApprovalOutcomesSpec(List.of(),
                List.of(new ApprovalFieldSpec("a__c", "1"), new ApprovalFieldSpec("b__c", "1"),
                        new ApprovalFieldSpec("c__c", "1"), new ApprovalFieldSpec("d__c", "1"),
                        new ApprovalFieldSpec("e__c", "1")), List.of()), null)).containsKey("outcomes.rejected");
    }

    @Test
    void noMessageRepeatsWhatAPersonTyped() {
        Map<String, List<String>> found = problems("SECRET-CRITERIA ((", List.of(
                new ApprovalStepSpec("SECRET name", "SECRET label", "SECRET", List.of(
                        new ApprovalApproverSpec("SECRET", "SECRET-VALUE")))),
                new ApprovalOutcomesSpec(List.of(new ApprovalFieldSpec("SECRET field", "SECRET-LITERAL")), List.of(),
                        List.of()), new ApprovalApproverSpec("SECRET", "SECRET-VALUE"));

        assertThat(found.values().stream().flatMap(List::stream).toList()).noneMatch(text -> text.contains("SECRET-"));
    }
}
