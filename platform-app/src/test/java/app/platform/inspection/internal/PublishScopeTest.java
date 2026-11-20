package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.inspection.internal.AudienceSuggestions.Held;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which locations a person may publish to (FA15, ADR-0265), with no database: inside the locations of the suggestion,
 * outside, none left, no suggestion, and the person who holds the ability to publish to any location.
 */
class PublishScopeTest {

    private static final Held TWO_PLACES = new Held(List.of(new Place("PLANT1", "PRD"), new Place("PLANT1", "QA"),
            new Place("PLANT2", null)), 0);

    private static AudiencePick pick(String location, String department) {
        return new AudiencePick(location, department);
    }

    @Test
    void aLimitedPersonMayPublishToTheLocationsTheApprovalChoseAndNoOther() {
        PublishScope scope = PublishScope.of(false, TWO_PLACES);

        assertThat(scope.limited()).isTrue();
        assertThat(scope.locations()).containsExactlyInAnyOrder("PLANT1", "PLANT2");
        assertThat(scope.outside(List.of(pick("PLANT1", "STORES"), pick("PLANT2", "PRD")))).isEmpty();
        assertThat(scope.outside(List.of(pick("PLANT1", "PRD"), pick("PLANT3", "PRD"), pick("PLANT9", "QA"))))
                .containsExactly(1, 2);
    }

    @Test
    void aDepartmentTheApprovalDidNotTickIsAHintNotALimitInsideAnAllowedLocation() {
        PublishScope scope = PublishScope.of(false, new Held(List.of(new Place("PLANT1", "PRD")), 0));

        assertThat(scope.outside(List.of(pick("PLANT1", "MAINT")))).isEmpty();
    }

    @Test
    void aTemplateWithNoSuggestionIsNotLimited() {
        PublishScope scope = PublishScope.of(false, new Held(List.of(), 0));

        assertThat(scope.limited()).isFalse();
        assertThat(scope.noneLeft()).isFalse();
        assertThat(scope.outside(List.of(pick("ANY", "ONE")))).isEmpty();
        assertThat(scope).isSameAs(PublishScope.unlimited());
    }

    @Test
    void aSuggestionWithEveryPlaceNoLongerOfferedLeavesNothingForALimitedPerson() {
        PublishScope scope = PublishScope.of(false, new Held(List.of(), 2));

        assertThat(scope.limited()).isTrue();
        assertThat(scope.noneLeft()).isTrue();
        assertThat(scope.outside(List.of(pick("PLANT1", "PRD")))).containsExactly(0);
    }

    @Test
    void aPersonWithTheAbilityIsNeverLimitedWhateverTheSuggestionSays() {
        assertThat(PublishScope.of(true, TWO_PLACES).limited()).isFalse();
        PublishScope none = PublishScope.of(true, new Held(List.of(), 3));
        assertThat(none.limited()).isFalse();
        assertThat(none.noneLeft()).isFalse();
        assertThat(none.outside(List.of(pick("PLANT9", "QA")))).isEmpty();
    }

    @Test
    void theWordsOfARefusalRepeatNoCodeAndThePrintingShowsNone() {
        assertThat(PublishScope.OUTSIDE).isEqualTo("This location was not chosen for this document.");
        assertThat(PublishScope.NONE_LEFT).doesNotContain("PLANT");
        assertThat(PublishScope.of(false, TWO_PLACES).toString()).doesNotContain("PLANT");
    }
}
