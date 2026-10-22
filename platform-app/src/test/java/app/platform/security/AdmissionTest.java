package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platformapi.QueryFilterNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The closed description of what a member may reach (ADR-0115): how conditions combine and what they print. */
class AdmissionTest {

    private static final UUID ONE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID TWO = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID GROUP = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final QueryFilterNode CONDITION = new QueryFilterNode("EQ", null, "stage__c", List.of("Open"));

    @Test
    void aPartThatAdmitsEverythingMakesTheWholeAdmitEverything() {
        Admission combined = Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ONE)), Admission.everything(),
                new Admission.SharedWith(Set.of(Grantee.group(GROUP)), ShareLevel.READ)));

        assertThat(combined).isInstanceOf(Admission.Everything.class);
    }

    @Test
    void nothingDisappearsAndNoPartsAtAllIsNothing() {
        assertThat(Admission.anyOf(List.of())).isInstanceOf(Admission.Nothing.class);
        assertThat(Admission.anyOf(List.of(Admission.nothing(), Admission.nothing())))
                .isInstanceOf(Admission.Nothing.class);
        assertThat(Admission.anyOf(List.of(Admission.nothing(), new Admission.OwnedBy(Set.of(ONE)))))
                .isEqualTo(new Admission.OwnedBy(Set.of(ONE)));
    }

    @Test
    void theOwnersOfSeveralPartsBecomeOnePart() {
        Admission combined = Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ONE)),
                new Admission.OwnedBy(Set.of(TWO, ONE))));

        assertThat(combined).isEqualTo(new Admission.OwnedBy(Set.of(ONE, TWO)));
    }

    @Test
    void theGranteesOfSharesAtTheSameLevelBecomeOnePartAndLevelsStayApart() {
        Admission combined = Admission.anyOf(List.of(
                new Admission.SharedWith(Set.of(Grantee.user(ONE)), ShareLevel.READ),
                new Admission.SharedWith(Set.of(Grantee.group(GROUP)), ShareLevel.READ),
                new Admission.SharedWith(Set.of(Grantee.user(TWO)), ShareLevel.READ_WRITE)));

        assertThat(combined).isInstanceOf(Admission.AnyOf.class);
        assertThat(((Admission.AnyOf) combined).parts()).containsExactly(
                new Admission.SharedWith(Set.of(Grantee.user(ONE), Grantee.group(GROUP)), ShareLevel.READ),
                new Admission.SharedWith(Set.of(Grantee.user(TWO)), ShareLevel.READ_WRITE));
    }

    @Test
    void aShareIsMatchedByKindAndIdentifierSoTheSameIdentifierOfAnotherKindIsAnotherGrantee() {
        Admission combined = Admission.anyOf(List.of(
                new Admission.SharedWith(Set.of(Grantee.role(ONE)), ShareLevel.READ),
                new Admission.SharedWith(Set.of(Grantee.roleAndSubordinates(ONE), Grantee.group(ONE)),
                        ShareLevel.READ)));

        assertThat(combined).isEqualTo(new Admission.SharedWith(Set.of(Grantee.role(ONE),
                Grantee.roleAndSubordinates(ONE), Grantee.group(ONE)), ShareLevel.READ));
        assertThat(Grantee.role(ONE)).isNotEqualTo(Grantee.roleAndSubordinates(ONE))
                .isNotEqualTo(Grantee.user(ONE));
    }

    @Test
    void combinationsInsideCombinationsAreFlattenedAndASinglePartStandsAlone() {
        Admission inner = Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ONE)),
                new Admission.Meets(GROUP, CONDITION)));

        Admission outer = Admission.anyOf(List.of(inner, new Admission.OwnedBy(Set.of(TWO))));

        assertThat(outer).isInstanceOf(Admission.AnyOf.class);
        assertThat(((Admission.AnyOf) outer).parts()).containsExactly(new Admission.OwnedBy(Set.of(ONE, TWO)),
                new Admission.Meets(GROUP, CONDITION));
        assertThat(Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ONE)))))
                .isEqualTo(new Admission.OwnedBy(Set.of(ONE)));
    }

    @Test
    void theSameMeaningGivesTheSameTreeWhateverTheOrder() {
        Admission first = Admission.anyOf(List.of(new Admission.OwnedBy(Set.of(ONE)),
                new Admission.SharedWith(Set.of(Grantee.group(GROUP)), ShareLevel.READ)));
        Admission second = Admission.anyOf(List.of(
                new Admission.SharedWith(Set.of(Grantee.group(GROUP)), ShareLevel.READ),
                new Admission.OwnedBy(Set.of(ONE))));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void nothingThatBelongsToTheOrganizationIsPrinted() {
        String printed = List.of(new Admission.OwnedBy(Set.of(ONE)),
                new Admission.SharedWith(Set.of(Grantee.group(GROUP)),
                ShareLevel.READ), new Admission.Meets(GROUP, CONDITION)).toString();

        assertThat(printed).doesNotContain(ONE.toString())
                .doesNotContain("stage__c").doesNotContain("Open");
        assertThat(new Admission.SharedWith(Set.of(Grantee.role(ONE), Grantee.user(TWO)), ShareLevel.READ).toString())
                .doesNotContain(ONE.toString()).doesNotContain(TWO.toString());
    }

    @Test
    void aRecordAccessChoosesTheAdmissionOfTheAction() {
        RecordAccess access = new RecordAccess("Deal__c", Admission.everything(),
                new Admission.OwnedBy(Set.of(ONE)), Admission.nothing(), false);

        assertThat(access.forAction(RecordAction.READ)).isInstanceOf(Admission.Everything.class);
        assertThat(access.forAction(RecordAction.EDIT)).isEqualTo(new Admission.OwnedBy(Set.of(ONE)));
        assertThat(access.forAction(RecordAction.DELETE)).isInstanceOf(Admission.Nothing.class);
        assertThat(access.unrestricted()).isFalse();
        assertThat(RecordAccess.everything("Deal__c").unrestricted()).isTrue();
        assertThat(RecordAccess.nothing("Deal__c").forAction(RecordAction.READ)).isInstanceOf(Admission.Nothing.class);
    }

    @Test
    void theLevelsOfSharesAreOrderedAndNoShareGivesTheRightToDelete() {
        assertThat(ShareLevel.READ_WRITE.covers(ShareLevel.READ)).isTrue();
        assertThat(ShareLevel.READ.covers(ShareLevel.READ_WRITE)).isFalse();
        assertThat(ShareLevel.neededFor(RecordAction.READ)).contains(ShareLevel.READ);
        assertThat(ShareLevel.neededFor(RecordAction.EDIT)).contains(ShareLevel.READ_WRITE);
        assertThat(ShareLevel.neededFor(RecordAction.DELETE)).isEmpty();
        assertThat(ShareLevel.ofValue(2)).contains(ShareLevel.READ_WRITE);
        assertThat(ShareLevel.ofValue(3)).isEmpty();
    }

    @Test
    void anObjectActionMapsToItsRecordAction() {
        assertThat(RecordAction.of(ObjectAction.READ)).isEqualTo(RecordAction.READ);
        assertThat(RecordAction.of(ObjectAction.UPDATE)).isEqualTo(RecordAction.EDIT);
        assertThat(RecordAction.of(ObjectAction.DELETE)).isEqualTo(RecordAction.DELETE);
        assertThat(RecordAction.of(ObjectAction.CREATE)).isNull();
        assertThat(RecordAction.of(ObjectAction.VIEW_ALL)).isNull();
        assertThat(RecordAction.of(ObjectAction.MODIFY_ALL)).isNull();
    }
}
