package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The objects whose records may own a sheet (FA3, ADR-0186): what the shipped solutions declare, and nothing else. */
class ShippedSheetOwnersTest {

    private static SolutionBundle bundle(String key, List<String> owners) {
        return new SolutionBundle(key, key + "-feature", "Label", "", new SolutionBundle.Application(key, "App", "",
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Overview", "", "overview", ""))),
                List.of("Widget", "Gadget"), owners, List.of(new SolutionBundle.Policy("reader", "Reader", "d",
                        Map.of("Widget", Set.of(ObjectAction.READ)))));
    }

    @Test
    void theOwnersAreWhatTheSolutionsDeclareAndNothingElse() {
        ShippedSheetOwners owners = new ShippedSheetOwners(new Solutions(List.of(bundle("one", List.of("Widget")),
                bundle("two", List.of("Gadget")), bundle("three", List.of()))));

        assertThat(owners.owners()).containsExactlyInAnyOrder("Widget", "Gadget");
        assertThat(owners.owners()).doesNotContain("Account", "InspectionTemplate");
    }

    @Test
    void noSolutionDeclaringAnOwnerMeansNoOwner() {
        assertThat(new ShippedSheetOwners(new Solutions(List.of())).owners()).isEmpty();
        assertThat(new ShippedSheetOwners(new Solutions(List.of(bundle("one", List.of())))).owners()).isEmpty();
    }

    @Test
    void theAnswerCannotBeChangedByWhoAsks() {
        ShippedSheetOwners owners = new ShippedSheetOwners(new Solutions(List.of(bundle("one", List.of("Widget")))));

        assertThatThrownBy(() -> owners.owners().add("Account")).isInstanceOf(UnsupportedOperationException.class);
    }
}
