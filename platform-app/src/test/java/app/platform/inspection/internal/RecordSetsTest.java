package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import app.platform.sharedkernel.solution.NumberSeriesDeclarations;
import app.platform.sharedkernel.solution.ObjectRoleDeclarations;
import app.platform.sharedkernel.solution.ObjectRoles;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Which set of records an application, or an object, means (FA13B, ADR-0226), with no database: the declarations are
 * the same kind of data the solution files give. What it must do: find the set of an application the organization has,
 * answer every other case in the same words, share one set between applications that declare the same objects, and
 * stop the start when a declaration is not sound.
 */
class RecordSetsTest {

    private static final TenantContext MEMBER = new TenantContext(new TenantId(UUID.randomUUID()), UUID.randomUUID(),
            UUID.randomUUID());

    private final TenantContexts contexts = new TenantContexts();

    private RecordSets sets(List<String> available) {
        return RecordSetsFixture.sets(List.of(
                RecordSetsFixture.roles("inspection", "inspection", RecordSetsFixture.QMS),
                RecordSetsFixture.roles("document-management", "document-management", RecordSetsFixture.QMS),
                RecordSetsFixture.roles("environment-management", "environment-management",
                        RecordSetsFixture.ENVIRONMENT)), available, contexts);
    }

    private <T> T asMember(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        contexts.run(MEMBER, () -> result.set(work.get()));
        return result.get();
    }

    /** The words of the NOT_FOUND the work ends in, asked as a member. */
    private String notFoundWords(Supplier<?> work) {
        AtomicReference<String> words = new AtomicReference<>();
        contexts.run(MEMBER, () -> assertThatThrownBy(work::get).isInstanceOfSatisfying(ApiException.class,
                refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    words.set(refused.getMessage());
                }));
        return words.get();
    }

    @Test
    void anApplicationTheOrganizationHasIsFoundWithItsOwnSet() {
        RecordSets sets = sets(List.of("inspection", "environment-management"));

        assertThat(asMember(() -> sets.require("inspection"))).isEqualTo(RecordSetsFixture.QMS);
        assertThat(asMember(() -> sets.require("environment-management"))).isEqualTo(RecordSetsFixture.ENVIRONMENT);
    }

    @Test
    void twoApplicationsThatDeclareTheSameObjectsShareOneSet() {
        RecordSets sets = sets(List.of("document-management"));

        assertThat(asMember(() -> sets.require("document-management"))).isEqualTo(RecordSetsFixture.QMS);
        assertThat(sets.templates()).containsExactly("InspectionTemplate", "EnvironmentTemplate");
        assertThat(sets.fills()).containsExactly("InspectionFill", "EnvironmentFill");
        assertThat(sets.requests()).containsExactly("InspectionChangeRequest", "EnvironmentChangeRequest");
    }

    @Test
    void anUnknownApplicationOneTheOrganizationLacksAndNoApplicationAreAllNotFoundInOneWords() {
        RecordSets sets = sets(List.of("inspection"));

        String unknown = notFoundWords(() -> sets.require("no-such-application"));
        String lacking = notFoundWords(() -> sets.require("environment-management"));
        String none = notFoundWords(() -> sets.require(null));

        assertThat(unknown).isNotBlank().isEqualTo(lacking).isEqualTo(none);
    }

    @Test
    void whereNoMemberIsInvolvedTheAnswerIsTheSame() {
        RecordSets sets = sets(List.of("inspection"));

        assertThatThrownBy(() -> sets.require("inspection")).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void anObjectIsAskedAboutByItsPartAndAnObjectOfAnotherPartIsNotFound() {
        RecordSets sets = sets(List.of());

        assertThat(sets.ofTemplate("EnvironmentTemplate")).contains(RecordSetsFixture.ENVIRONMENT);
        assertThat(sets.ofFill("InspectionFill")).contains(RecordSetsFixture.QMS);
        assertThat(sets.ofRequest("EnvironmentChangeRequest")).contains(RecordSetsFixture.ENVIRONMENT);
        assertThat(sets.ofTemplate("EnvironmentFill")).isEmpty();
        assertThat(sets.ofFill("DepartmentGroup")).isEmpty();
    }

    @Test
    void aLinkNamesAnApplicationThatShowsTheSetAndTheOrganizationHas() {
        RecordSets onlyDocuments = sets(List.of("document-management"));
        RecordSets both = sets(List.of("inspection", "document-management"));

        // The Quality Management System comes first, but only an application the organization has is named.
        assertThat(asMember(() -> onlyDocuments.applicationFor(RecordSetsFixture.QMS)))
                .isEqualTo("document-management");
        assertThat(asMember(() -> both.applicationFor(RecordSetsFixture.QMS))).isEqualTo("inspection");
        // With none available the first declared is named: the link then leads to the plain "not available" page.
        assertThat(asMember(() -> sets(List.of()).applicationFor(RecordSetsFixture.ENVIRONMENT)))
                .isEqualTo("environment-management");
    }

    // ---- the start is stopped by a declaration that is not sound ----

    private static ObjectRoleDeclarations declaring(ObjectRoles... roles) {
        return new ObjectRoleDeclarations() {
            @Override
            public List<ObjectRoles> declared() {
                return List.of(roles);
            }

            @Override
            public Optional<ObjectRoles> available(String application) {
                return Optional.empty();
            }
        };
    }

    private static NumberSeriesDeclarations series(String... templateObjects) {
        return () -> java.util.Arrays.stream(templateObjects)
                .map(object -> new NumberSeriesDeclaration(object.toLowerCase() + "-series", object, "number", "",
                        Map.of())).toList();
    }

    @Test
    void anApplicationThatLeavesOutAPartStopsTheStartAndNamesTheApplication() {
        ObjectRoles missing = new ObjectRoles("odd", "odd", Map.of(RecordSets.TEMPLATE, "OddTemplate",
                RecordSets.FILL, "OddFill"));

        assertThatThrownBy(() -> new RecordSets(declaring(missing), series("OddTemplate"), contexts))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("application 'odd'")
                .hasMessageContaining("must name the parts");
    }

    @Test
    void aTemplateObjectWithNoSeriesOrTwoStopsTheStart() {
        ObjectRoles odd = RecordSetsFixture.roles("odd", "odd", new RecordSet("OddTemplate", "OddFill",
                "OddRequest", "x"));

        assertThatThrownBy(() -> new RecordSets(declaring(odd), series(), contexts))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one number series")
                .hasMessageContaining("found 0");
        assertThatThrownBy(() -> new RecordSets(declaring(odd), series("OddTemplate", "OddTemplate"), contexts))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("found 2");
    }

    @Test
    void anObjectInTwoDifferentSetsStopsTheStart() {
        ObjectRoles one = RecordSetsFixture.roles("one", "one", new RecordSet("ATemplate", "AFill", "ARequest", "x"));
        ObjectRoles two = RecordSetsFixture.roles("two", "two", new RecordSet("BTemplate", "AFill", "BRequest", "y"));

        assertThatThrownBy(() -> new RecordSets(declaring(one, two), series("ATemplate", "BTemplate"), contexts))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'AFill' belongs to two sets");
    }

    // ---- options of an application (MC1, ADR-0257) ----

    private static ObjectRoles withOptions(Map<String, String> options) {
        return new ObjectRoles("odd", "odd", Map.of(RecordSets.TEMPLATE, "OddTemplate", RecordSets.FILL, "OddFill",
                RecordSets.REQUEST, "OddRequest"), options);
    }

    @Test
    void theOptionsOfAnApplicationReachItsSetAndNameTheWayItPublishesAndWhatItsFooterCarries() {
        RecordSets sets = new RecordSets(declaring(withOptions(Map.of(RecordSets.FOOTER_DATE, "true",
                RecordSets.PUBLISH_TO, RecordSets.PLACES, RecordSets.PUBLISH_DEPARTMENT, "PLANTHEAD"))),
                series("OddTemplate"), contexts);

        RecordSet set = sets.ofTemplate("OddTemplate").orElseThrow();
        assertThat(set.footerDate()).isTrue();
        assertThat(set.placesOnly()).isTrue();
        assertThat(set.placeDepartment()).isEqualTo("PLANTHEAD");
        RecordSet plain = RecordSetsFixture.QMS;
        assertThat(plain.footerDate()).isFalse();
        assertThat(plain.placesOnly()).isFalse();
        assertThat(plain.placeDepartment()).isNull();
    }

    @Test
    void anOptionTheFunctionsDoNotKnowOrPlacesWithoutADepartmentStopTheStart() {
        assertThatThrownBy(() -> new RecordSets(declaring(withOptions(Map.of("surprise", "yes"))),
                series("OddTemplate"), contexts)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application 'odd'").hasMessageContaining("surprise");
        assertThatThrownBy(() -> new RecordSets(declaring(withOptions(Map.of(RecordSets.PUBLISH_TO,
                RecordSets.PLACES))), series("OddTemplate"), contexts)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("go together");
        assertThatThrownBy(() -> new RecordSets(declaring(withOptions(Map.of(RecordSets.PUBLISH_TO, "everywhere",
                RecordSets.PUBLISH_DEPARTMENT, "X"))), series("OddTemplate"), contexts))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("publishTo");
    }
}
