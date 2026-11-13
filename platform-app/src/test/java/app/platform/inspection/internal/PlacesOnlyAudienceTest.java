package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.TemplateViews.AudienceDepartment;
import app.platform.inspection.internal.TemplateViews.AudienceLocation;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An application that publishes to places alone offers each place with its one department and leaves out the places
 * where that department is not part of the setup (MC1, ADR-0257); the other applications see the lists as they are. No
 * database.
 */
class PlacesOnlyAudienceTest {

    private static final AudienceOptionsView ALL = new AudienceOptionsView(List.of(
            new AudienceLocation("PLANT1", "Plant 1", List.of(new AudienceDepartment("PRD", "Production", true),
                    new AudienceDepartment("PLANTHEAD", "Plant head", true))),
            new AudienceLocation("PLANT2", "Plant 2", List.of(new AudienceDepartment("PRD", "Production", true),
                    new AudienceDepartment("PLANTHEAD", "Plant head", false))),
            new AudienceLocation("HEAD", "Head office", List.of(new AudienceDepartment("HR", "Human resources",
                    true)))));

    private static RecordSet places() {
        return new RecordSet("ATemplate", "AFill", "ARequest", "a-series", Map.of(RecordSets.PUBLISH_TO,
                RecordSets.PLACES, RecordSets.PUBLISH_DEPARTMENT, "PLANTHEAD"));
    }

    @Test
    void aPlacesOnlySetOffersEachPlaceWithTheOneDepartmentAndSaysSo() {
        AudienceOptionsView offered = TemplatePublishing.forSet(places(), ALL);

        assertThat(offered.placesOnly()).isTrue();
        assertThat(offered.placeDepartment()).isEqualTo("PLANTHEAD");
        assertThat(offered.locations()).extracting(AudienceLocation::value).containsExactly("PLANT1", "PLANT2");
        assertThat(offered.locations()).allSatisfy(place -> assertThat(place.departments())
                .extracting(AudienceDepartment::value).containsExactly("PLANTHEAD"));
        assertThat(offered.locations().get(1).departments().get(0).configured())
                .as("a place with no group is offered but marked").isFalse();
    }

    @Test
    void anOrdinarySetSeesTheListsAsTheyAre() {
        AudienceOptionsView offered = TemplatePublishing.forSet(RecordSetsFixture.QMS, ALL);

        assertThat(offered).isSameAs(ALL);
        assertThat(offered.placesOnly()).isFalse();
        assertThat(offered.placeDepartment()).isNull();
    }
}
