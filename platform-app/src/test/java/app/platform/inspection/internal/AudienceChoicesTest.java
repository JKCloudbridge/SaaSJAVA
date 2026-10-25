package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.Choice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule of the choice an approver sends at a step that asks for one (FA6G, ADR-0200): a location is required, a
 * department is optional, and nothing is accepted that says more than the approver ticked. No database.
 */
class AudienceChoicesTest {

    private static Choice location(String code) {
        return new Choice("location", code);
    }

    private static Choice department(String code) {
        return new Choice("department", code);
    }

    private static ApprovalHookRefusal refusal(List<Choice> choices) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApprovalHookRefusal.class,
                () -> AudienceChoices.parse(choices));
    }

    @Test
    void aLocationAloneIsEnough() {
        assertThat(AudienceChoices.parse(List.of(location("PLANT1")))).containsExactly(new Place("PLANT1", null));
    }

    @Test
    void noLocationIsRefusedWithTheFixedWords() {
        ApprovalHookRefusal none = refusal(List.of());
        assertThat(none.reason()).isEqualTo("location_needed");
        assertThat(none.words()).isEqualTo(AudienceChoices.LOCATION_NEEDED);
        assertThat(refusal(List.of(department("PLANT1/PRD"))).reason()).isIn("location_needed", "choice_invalid");
    }

    @Test
    void departmentsAreOptionalAndStayWithTheirLocation() {
        List<Place> places = AudienceChoices.parse(List.of(location("PLANT1"), location("HEAD"),
                department("PLANT1/QLT"), department("PLANT1/PRD")));

        assertThat(places).containsExactly(new Place("PLANT1", "QLT"), new Place("PLANT1", "PRD"),
                new Place("HEAD", null));
    }

    @Test
    void aDepartmentOfALocationThatWasNotChosenIsRefused() {
        assertThat(refusal(List.of(location("PLANT1"), department("HEAD/HR"))).reason()).isEqualTo("choice_invalid");
    }

    @Test
    void repeatedPairsAndUnknownKeysAreRefused() {
        assertThat(refusal(List.of(location("PLANT1"), location("PLANT1"))).reason()).isEqualTo("choice_invalid");
        assertThat(refusal(List.of(location("PLANT1"), department("PLANT1/PRD"), department("PLANT1/PRD"))).reason())
                .isEqualTo("choice_invalid");
        assertThat(refusal(List.of(location("PLANT1"), new Choice("group", "X"))).reason())
                .isEqualTo("choice_invalid");
    }

    @Test
    void malformedCodesAreRefusedAndTheWordsNeverRepeatThem() {
        for (String bad : List.of("", "has space", "way-too-long-code", "a/b", "<b>")) {
            ApprovalHookRefusal refused = refusal(List.of(location(bad)));
            assertThat(refused.words()).doesNotContain(bad.isEmpty() ? "\u0000" : bad);
        }
        assertThat(refusal(List.of(location("PLANT1"), department("PLANT1/"))).reason()).isEqualTo("choice_invalid");
        assertThat(refusal(List.of(location("PLANT1"), department("PLANT1/A/B"))).reason())
                .isEqualTo("choice_invalid");
    }

    @Test
    void moreThanTheMostPlacesIsRefused() {
        List<Choice> many = new ArrayList<>();
        for (int i = 0; i <= AudienceChoices.MOST_PLACES; i++) {
            many.add(location("L" + i));
        }
        assertThat(refusal(many).reason()).isEqualTo("choice_invalid");
        assertThat(AudienceChoices.parse(many.subList(0, AudienceChoices.MOST_PLACES)))
                .hasSize(AudienceChoices.MOST_PLACES);
    }

    @Test
    void aChoiceAndAPlacePrintNothingOfTheirCodes() {
        assertThat(location("SECRETCODE").toString()).doesNotContain("SECRETCODE");
        assertThat(new Place("SECRETCODE", "OTHER").toString()).doesNotContain("SECRETCODE").doesNotContain("OTHER");
    }
}
