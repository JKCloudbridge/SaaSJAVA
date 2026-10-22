package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.inspection.internal.ChangeAnchor.Kind;
import app.platform.sheets.SheetLifecycle.Layout;
import app.platform.sheets.SheetStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The place a comment points at (FA5, ADR-0191, ADR-0173), with no database: the kind from the identifiers that are
 * given, the shape of an identifier, whether a place is in a version, and the label kept with it.
 */
class ChangeAnchorTest {

    private static final Layout LAYOUT = new Layout(SheetStatus.ACTIVE,
            Map.of("cA", "Torque", "cB", ""), Map.of("h1", "", "r1", "First row"));

    @Test
    void theKindFollowsWhichIdentifiersAreGiven() {
        assertThat(new ChangeAnchor(null, null).kind()).isEqualTo(Kind.TEMPLATE);
        assertThat(new ChangeAnchor("r1", null).kind()).isEqualTo(Kind.ROW);
        assertThat(new ChangeAnchor(null, "cA").kind()).isEqualTo(Kind.COLUMN);
        assertThat(new ChangeAnchor("r1", "cA").kind()).isEqualTo(Kind.CELL);
        assertThat(ChangeAnchor.WHOLE.kind()).isEqualTo(Kind.TEMPLATE);
    }

    @Test
    void aBlankIdentifierIsNoIdentifier() {
        assertThat(new ChangeAnchor("  ", "").kind()).isEqualTo(Kind.TEMPLATE);
        assertThat(new ChangeAnchor(" r1 ", null).rowId()).isEqualTo("r1");
    }

    @Test
    void anIdentifierIsShapedLikeTheGridsOwn() {
        assertThat(new ChangeAnchor("r1", "cA").shapeProblem()).isEmpty();
        assertThat(new ChangeAnchor("a".repeat(40), null).shapeProblem()).isEmpty();
        assertThat(new ChangeAnchor("a".repeat(41), null).shapeProblem()).isPresent();
        assertThat(new ChangeAnchor("r 1", null).shapeProblem()).isPresent();
        assertThat(new ChangeAnchor(null, "c:A").shapeProblem()).isPresent();
        assertThat(new ChangeAnchor("r1;drop", null).shapeProblem().orElseThrow()).doesNotContain("drop");
    }

    @Test
    void aPlaceIsInAVersionWhenEveryIdentifierItNamesIs() {
        assertThat(new ChangeAnchor(null, null).presentIn(LAYOUT)).isTrue();
        assertThat(new ChangeAnchor("r1", null).presentIn(LAYOUT)).isTrue();
        assertThat(new ChangeAnchor("h1", "cA").presentIn(LAYOUT)).as("a header row is a row").isTrue();
        assertThat(new ChangeAnchor("gone", null).presentIn(LAYOUT)).isFalse();
        assertThat(new ChangeAnchor(null, "gone").presentIn(LAYOUT)).isFalse();
        assertThat(new ChangeAnchor("r1", "gone").presentIn(LAYOUT)).isFalse();
        assertThat(new ChangeAnchor("cA", null).presentIn(LAYOUT)).as("a column is not a row").isFalse();
    }

    @Test
    void theLabelNamesThePlaceAsItWasAndIsEmptyWhenNothingNamesIt() {
        assertThat(new ChangeAnchor(null, null).label(LAYOUT)).isEmpty();
        assertThat(new ChangeAnchor("r1", null).label(LAYOUT)).isEqualTo("First row");
        assertThat(new ChangeAnchor(null, "cA").label(LAYOUT)).isEqualTo("Torque");
        assertThat(new ChangeAnchor("r1", "cA").label(LAYOUT)).isEqualTo("Torque / First row");
        assertThat(new ChangeAnchor("r1", "cB").label(LAYOUT)).as("a column with no name").isEqualTo("First row");
        assertThat(new ChangeAnchor("h1", "cA").label(LAYOUT)).isEqualTo("Torque");
        assertThat(new ChangeAnchor("gone", null).label(LAYOUT)).isEmpty();
    }

    @Test
    void aLongLabelIsCutToTheMostALabelMayHave() {
        Layout wide = new Layout(SheetStatus.ACTIVE, Map.of("c", "x".repeat(150)), Map.of("r", "y".repeat(150)));

        assertThat(new ChangeAnchor("r", "c").label(wide)).hasSize(200);
    }
}
