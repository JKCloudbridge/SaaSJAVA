package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.inspection.internal.AudienceChoices.Publish;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.Choice;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The choice of the last approval of a change request (FA8, ADR-0202), with no database: the places as for a
 * suggestion,
 * a department for every location, and the two options of the sheet as pairs of the key {@code option}.
 */
class AudienceChoicesPublishTest {

    private static Choice location(String code) {
        return new Choice("location", code);
    }

    private static Choice department(String code) {
        return new Choice("department", code);
    }

    private static Choice option(String code) {
        return new Choice("option", code);
    }

    private static ApprovalHookRefusal refusal(List<Choice> choices) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApprovalHookRefusal.class,
                () -> AudienceChoices.parsePublish(choices));
    }

    @Test
    void aLocationWithADepartmentIsPublishedToAndTheOptionsAreOffByDefault() {
        Publish publish = AudienceChoices.parsePublish(List.of(location("PLANT1"), department("PLANT1/PRD")));

        assertThat(publish.places()).containsExactly(new Place("PLANT1", "PRD"));
        assertThat(publish.viewOnly()).isFalse();
        assertThat(publish.allowDownload()).isFalse();
    }

    @Test
    void theOptionsAreSwitchedOnByTheirPairs() {
        Publish both = AudienceChoices.parsePublish(List.of(location("PLANT1"), department("PLANT1/PRD"),
                option("viewOnly"), option("allowDownload")));
        Publish viewOnly = AudienceChoices.parsePublish(List.of(location("PLANT1"), department("PLANT1/PRD"),
                option("viewOnly")));

        assertThat(both.viewOnly()).isTrue();
        assertThat(both.allowDownload()).isTrue();
        assertThat(viewOnly.viewOnly()).isTrue();
        assertThat(viewOnly.allowDownload()).isFalse();
    }

    @Test
    void aLocationWithNoDepartmentIsRefusedBecauseATemplateIsPublishedToDepartments() {
        ApprovalHookRefusal refused = refusal(List.of(location("PLANT1")));

        assertThat(refused.reason()).isEqualTo("department_needed");
        assertThat(refused.words()).isEqualTo(AudienceChoices.DEPARTMENT_NEEDED);
        assertThat(refusal(List.of(location("PLANT1"), department("PLANT1/PRD"), location("HEAD"))).reason())
                .isEqualTo("department_needed");
    }

    @Test
    void noLocationAtAllIsRefusedAsForASuggestion() {
        assertThat(refusal(List.of(option("viewOnly"))).reason()).isEqualTo("location_needed");
    }

    @Test
    void anUnknownOptionIsRefusedWithoutRepeatingIt() {
        ApprovalHookRefusal refused = refusal(List.of(location("PLANT1"), department("PLANT1/PRD"),
                option("DELETEEVERYTHING")));

        assertThat(refused.reason()).isEqualTo("choice_invalid");
        assertThat(refused.words()).doesNotContain("DELETEEVERYTHING");
    }

    @Test
    void aPublishChoiceRepeatsNothingOfTheCodesWhenPrinted() {
        Publish publish = AudienceChoices.parsePublish(List.of(location("SECRETCODE"), department("SECRETCODE/PRD")));

        assertThat(publish.toString()).doesNotContain("SECRETCODE");
    }
}
