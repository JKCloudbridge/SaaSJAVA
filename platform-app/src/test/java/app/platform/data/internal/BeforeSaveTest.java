package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.metadata.CompiledWorkflow;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.WorkflowDefinition;
import app.platform.observability.ErrorTracker;
import app.platformapi.WorkflowActionTypes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Before-save automation without a database (ADR-0160): what it sets, how it is bounded, and what it never does. The
 * resolver, the compiled workflows and the error tracking are stand-ins; the typing of what an automation sets is the
 * real code of the write pipeline.
 */
class BeforeSaveTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    private final MetadataResolver resolver = mock(MetadataResolver.class);
    private final ErrorTracker tracker = mock(ErrorTracker.class);
    private final List<CompiledWorkflow> automations = new ArrayList<>();

    private static FieldDefinition text(String name, int length) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, false,
                null, new TextConfiguration(length), false, 0);
    }

    private static final ObjectDefinition OBJECT = new ObjectDefinition("Thing__c", "Thing", "Things", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(text("a__c", 20), text("b__c", 20), text("short__c", 3)));

    private static WorkflowDefinition.Assignment literal(String field, String value) {
        return new WorkflowDefinition.Assignment(field, value, null);
    }

    private static WorkflowDefinition.Assignment copy(String field, String from) {
        return new WorkflowDefinition.Assignment(field, null, from);
    }

    /** An automation of the given name that sets the assignments when its condition gives the outcome. */
    private CompiledWorkflow automation(String name, boolean creating, ConditionOutcome outcome,
            WorkflowDefinition.Assignment... assignments) {
        WorkflowDefinition.Trigger trigger = new WorkflowDefinition.Trigger(creating
                ? WorkflowDefinition.Kind.BEFORE_CREATE : WorkflowDefinition.Kind.BEFORE_UPDATE, List.of(), null,
                null, null, null, null, null, null);
        WorkflowDefinition definition = new WorkflowDefinition(UUID.randomUUID(), UUID.randomUUID(), "Thing__c", name,
                name, "", true, 1, trigger, null, List.of(new WorkflowDefinition.Action(
                        WorkflowActionTypes.SET_FIELDS, "Set", Map.of(), List.of(assignments), null)), 0);
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(compiled.definition()).thenReturn(definition);
        when(compiled.evaluate(any(), any())).thenReturn(outcome);
        automations.add(compiled);
        return compiled;
    }

    private BeforeSave stage(int most, Duration budget, boolean enabled) {
        when(resolver.workflows("Thing__c")).thenReturn(List.copyOf(automations));
        return new BeforeSave(resolver, CLOCK, tracker, new SimpleMeterRegistry(),
                new BeforeSaveProperties(enabled, most, budget));
    }

    private BeforeSave stage() {
        return stage(10, Duration.ofMillis(500), true);
    }

    private BeforeSave.Changes run(BeforeSave stage, boolean creating, Map<String, Object> merged,
            Map<String, Object> held, Findings findings) {
        return stage.run(OBJECT, null, creating, merged, held, findings);
    }

    @Test
    void anObjectWithoutAutomationsIsLeftAloneAndAsksNothingElse() {
        BeforeSave stage = stage();

        assertThat(stage.any("Thing__c", true)).isFalse();
        assertThat(run(stage, true, Map.of("a__c", "x"), Map.of(), new Findings()).isEmpty()).isTrue();
    }

    @Test
    void itSetsALiteralAndACopyWhenTheConditionHolds() {
        automation("Tidy", true, ConditionOutcome.TRUE, literal("b__c", "fixed"), copy("short__c", "a__c"));
        Findings findings = new Findings();

        BeforeSave.Changes changes = run(stage(), true, Map.of("a__c", "abc"), Map.of(), findings);

        assertThat(findings.isEmpty()).isTrue();
        assertThat(changes.set()).containsEntry("b__c", "fixed").containsEntry("short__c", "abc");
        assertThat(changes.clear()).isEmpty();
    }

    @Test
    void aCreateRunsTheCreateAutomationsAndAnUpdateTheUpdateOnes() {
        automation("OnCreate", true, ConditionOutcome.TRUE, literal("b__c", "made"));
        automation("OnUpdate", false, ConditionOutcome.TRUE, literal("b__c", "changed"));
        BeforeSave stage = stage();

        assertThat(run(stage, true, Map.of(), Map.of(), new Findings()).set()).containsEntry("b__c", "made");
        assertThat(run(stage, false, Map.of(), Map.of(), new Findings()).set()).containsEntry("b__c", "changed");
    }

    @Test
    void aFalseConditionSetsNothingAndAnUnjudgeableOneIsSkippedAndReportedButNeverBlocksTheSave() {
        automation("No", true, ConditionOutcome.FALSE, literal("b__c", "never"));
        automation("Broken", true, ConditionOutcome.NOT_EVALUABLE, literal("a__c", "never"));
        Findings findings = new Findings();

        BeforeSave.Changes changes = run(stage(), true, Map.of(), Map.of(), findings);

        assertThat(changes.isEmpty()).isTrue();
        assertThat(findings.isEmpty()).isTrue();
        verify(tracker).track(any());
    }

    @Test
    void everyConditionIsJudgedOnTheRecordAsItWasBeforeAnyAutomationRanSoNothingCascades() {
        CompiledWorkflow first = automation("First", true, ConditionOutcome.TRUE, literal("b__c", "set by the first"));
        CompiledWorkflow second = automation("Second", true, ConditionOutcome.TRUE, literal("a__c", "set by second"));
        Map<String, Object> merged = new HashMap<>(Map.of("a__c", "original"));

        run(stage(), true, merged, Map.of(), new Findings());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> seenBySecond = ArgumentCaptor.forClass(Map.class);
        verify(second).evaluate(seenBySecond.capture(), any());
        assertThat(seenBySecond.getValue()).containsEntry("a__c", "original").doesNotContainKey("b__c");
        verify(first).evaluate(any(), any());
        assertThat(merged).as("the record the pipeline holds is not touched by the stage").containsOnlyKeys("a__c");
    }

    @Test
    void theyRunInTheOrderOfTheirNamesAndALaterOneWinsAFieldTwoOfThemSet() {
        automation("Zed", true, ConditionOutcome.TRUE, literal("b__c", "from zed"));
        automation("Alpha", true, ConditionOutcome.TRUE, literal("b__c", "from alpha"));

        BeforeSave.Changes changes = run(stage(), true, Map.of(), Map.of(), new Findings());

        assertThat(changes.set()).containsEntry("b__c", "from zed");
    }

    @Test
    void aCopyOfAFieldWithNoValueEmptiesTheTargetOfAnUpdateAndLeavesACreateAlone() {
        automation("Clear", false, ConditionOutcome.TRUE, copy("b__c", "a__c"));
        BeforeSave stage = stage();
        Map<String, Object> held = Map.of("b__c", "old");

        BeforeSave.Changes update = run(stage, false, new HashMap<>(held), held, new Findings());
        BeforeSave.Changes create = run(stage, false, Map.of(), Map.of(), new Findings());

        assertThat(update.clear()).containsExactly("b__c");
        assertThat(update.set()).isEmpty();
        assertThat(create.isEmpty()).isTrue();
    }

    @Test
    void aValueThatDoesNotFitRefusesTheSaveNamingTheFieldAndNeverTheValue() {
        automation("TooLong", true, ConditionOutcome.TRUE, literal("short__c", "SENTINEL-TOO-LONG"));
        Findings findings = new Findings();

        BeforeSave.Changes changes = run(stage(), true, Map.of(), Map.of(), findings);

        assertThat(changes.isEmpty()).isTrue();
        assertThat(findings.places()).containsExactly("short__c");
        assertThat(findings.at("short__c")).containsExactly(BeforeSave.MISFIT);
        assertThat(findings.at("short__c").toString()).doesNotContain("SENTINEL");
    }

    @Test
    void aFieldTheObjectDoesNotHaveOrCannotBeWrittenIsLeftAlone() {
        automation("Ghost", true, ConditionOutcome.TRUE, literal("ghost__c", "x"), literal("b__c", "kept"));

        BeforeSave.Changes changes = run(stage(), true, Map.of(), Map.of(), new Findings());

        assertThat(changes.set()).containsOnlyKeys("b__c");
    }

    @Test
    void onlyTheFirstFewByNameRunWhenThereAreMoreThanTheBound() {
        CompiledWorkflow a = automation("A", true, ConditionOutcome.TRUE, literal("a__c", "1"));
        CompiledWorkflow b = automation("B", true, ConditionOutcome.TRUE, literal("b__c", "2"));
        CompiledWorkflow c = automation("C", true, ConditionOutcome.TRUE, literal("short__c", "3"));

        run(stage(2, Duration.ofMillis(500), true), true, Map.of(), Map.of(), new Findings());

        verify(a).evaluate(any(), any());
        verify(b).evaluate(any(), any());
        verify(c, never()).evaluate(any(), any());
    }

    @Test
    void theSwitchTurnsThemAllOff() {
        automation("Tidy", true, ConditionOutcome.TRUE, literal("b__c", "fixed"));
        BeforeSave off = stage(10, Duration.ofMillis(500), false);

        assertThat(off.any("Thing__c", true)).isFalse();
        assertThat(run(off, true, Map.of(), Map.of(), new Findings()).isEmpty()).isTrue();
    }

    @Test
    void anOverrunOfTheTimeBudgetRefusesTheSaveWithWordsThatNameNothingAndReportsIt() {
        CompiledWorkflow slow = automation("Slow", true, ConditionOutcome.TRUE, literal("b__c", "x"));
        when(slow.evaluate(any(), any())).thenAnswer(call -> {
            Thread.sleep(60);
            return ConditionOutcome.TRUE;
        });
        automation("Next", true, ConditionOutcome.TRUE, literal("a__c", "y"));
        Findings findings = new Findings();

        BeforeSave.Changes changes = run(stage(10, Duration.ofMillis(20), true), true, Map.of(), Map.of(), findings);

        assertThat(changes.isEmpty()).isTrue();
        assertThat(findings.at(RecordValues.PROBLEMS_KEY)).containsExactly(BeforeSave.OVERRAN);
        verify(tracker).track(any());
    }

    @Test
    void theBoundsOfTheSettingsAreChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BeforeSaveProperties(true, 0,
                Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BeforeSaveProperties(true, 10,
                Duration.ofMillis(1))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BeforeSaveProperties(true, 10,
                Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
}
