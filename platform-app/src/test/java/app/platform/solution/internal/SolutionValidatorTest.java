package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What can only be checked against the rest of the platform (ADR-0181): the feature exists, every object a solution
 * names is a platform object that needs exactly its feature, every shipped object belongs to a solution, and no name is
 * used by two. The facts are handed in, so each mistake is tried on its own.
 */
class SolutionValidatorTest {

    private static ObjectDefinition object(String name, String feature, boolean kept) {
        return new ObjectDefinition(name, name, name + "s", "", DefinitionKind.STANDARD, null, false, 0, List.of(),
                feature, kept);
    }

    private static PlatformObjects platform(ObjectDefinition... objects) {
        return new PlatformObjects() {
            @Override
            public List<ObjectDefinition> all() {
                return List.of(objects);
            }

            @Override
            public Optional<ObjectDefinition> named(String apiName) {
                return all().stream().filter(object -> object.apiName().equals(apiName)).findFirst();
            }
        };
    }

    private static SolutionBundle bundle(String key, String feature, String application, List<String> objects,
            List<SolutionBundle.Entry> entries, List<SolutionBundle.Policy> policies) {
        return new SolutionBundle(key, feature, "Label", "", new SolutionBundle.Application(application, "App", "",
                entries), objects, policies);
    }

    private static SolutionBundle good() {
        return bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Overview", "", "home",
                        "Widget")),
                List.of(new SolutionBundle.Policy("reader", "Reader", "d",
                        Map.of("Widget", Set.of(ObjectAction.READ)))));
    }

    private static final PlatformObjects WIDGET = platform(object("Widget", "demo-feature", true));

    @Test
    void aSoundBundleAgainstTheRightFactsPasses() {
        assertThatCode(() -> SolutionValidator.validate(List.of(good()), WIDGET, Set.of("demo-feature")))
                .doesNotThrowAnyException();
    }

    @Test
    void theFeatureCheckCanWaitForTheDatabase() {
        assertThatCode(() -> SolutionValidator.validate(List.of(good()), WIDGET, null)).doesNotThrowAnyException();
    }

    @Test
    void aFeatureThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(good()), WIDGET, Set.of("another")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("solution 'demo'")
                .hasMessageContaining("'feature' is not a plan feature that exists");
    }

    @Test
    void anObjectThePlatformDoesNotDefineIsRefused() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(good()), platform(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'Widget' is not defined");
    }

    @Test
    void anObjectThatNeedsAnotherFeatureOrNoneIsRefused() {
        for (String feature : new String[] {"other-feature", null}) {
            assertThatThrownBy(() -> SolutionValidator.validate(List.of(good()),
                    platform(object("Widget", feature, true)), null)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not require the solution's feature");
        }
    }

    @Test
    void aShippedObjectNoSolutionBringsIsRefused() {
        PlatformObjects orphan = platform(object("Widget", "demo-feature", true), object("Orphan", "x-feature", false));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(good()), orphan, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'Orphan' requires a plan feature");
    }

    @Test
    void anObjectOfNoFeatureNeedsNoSolution() {
        PlatformObjects ordinary = platform(object("Widget", "demo-feature", true), object("Plain", null, false));

        assertThatCode(() -> SolutionValidator.validate(List.of(good()), ordinary, null)).doesNotThrowAnyException();
    }

    @Test
    void anEntryOrARightThatNamesAnObjectTheSolutionDoesNotBringIsRefused() {
        SolutionBundle entry = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.OBJECT, "", "Gadget", "", "")), List.of());
        SolutionBundle reads = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "O", "", "overview", "Gadget")),
                List.of());
        SolutionBundle right = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                good().application().entries(),
                List.of(new SolutionBundle.Policy("reader", "Reader", "d",
                        Map.of("Gadget", Set.of(ObjectAction.READ)))));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(entry), WIDGET, null))
                .hasMessageContaining("entries[0] names an object the solution does not bring");
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(reads), WIDGET, null))
                .hasMessageContaining("'readsObject' names an object the solution does not bring");
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(right), WIDGET, null))
                .hasMessageContaining("a right names 'Gadget'");
    }

    @Test
    void anApplicationOfOnlyHeadingsIsRefused() {
        SolutionBundle headings = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.HEADING, "Group", "", "", "")), List.of());

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(headings), WIDGET, null))
                .hasMessageContaining("only headings");
    }

    @Test
    void theDeleteRightOnAnObjectWhoseRecordsAreKeptIsRefused() {
        SolutionBundle deleting = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                good().application().entries(),
                List.of(new SolutionBundle.Policy("reader", "Reader", "d",
                        Map.of("Widget", Set.of(ObjectAction.READ, ObjectAction.DELETE)))));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(deleting), WIDGET, null))
                .hasMessageContaining("the delete right on 'Widget', whose records are kept");
        assertThatCode(() -> SolutionValidator.validate(List.of(deleting), platform(object("Widget", "demo-feature",
                false)), null)).doesNotThrowAnyException();
    }

    @Test
    void twoSolutionsMayNotShareAFeatureAnApplicationNameOrAnObject() {
        List<SolutionBundle> sameFeature = new ArrayList<>(List.of(good(), bundle("other", "demo-feature", "other",
                List.of(), good().application().entries(), List.of())));
        List<SolutionBundle> sameApplication = new ArrayList<>(List.of(good(), bundle("other", "other-feature", "demo",
                List.of(), good().application().entries(), List.of())));
        List<SolutionBundle> sameObject = new ArrayList<>(List.of(good(), bundle("other", "other-feature", "other",
                List.of("Widget"), good().application().entries(), List.of())));

        assertThatThrownBy(() -> SolutionValidator.validate(sameFeature, WIDGET, null))
                .hasMessageContaining("feature of another solution");
        assertThatThrownBy(() -> SolutionValidator.validate(sameApplication, WIDGET, null))
                .hasMessageContaining("application name is used by another solution");
        assertThatThrownBy(() -> SolutionValidator.validate(sameObject,
                platform(object("Widget", "demo-feature", true)), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApplicationNameThatEndsLikeAnOrganizationsOwnIsRefused() {
        SolutionBundle clash = bundle("demo", "demo-feature", "demo__c", List.of("Widget"),
                good().application().entries(),
                List.of());

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(clash), WIDGET, null))
                .hasMessageContaining("must not end like an organization's own");
    }

    @Test
    void aSheetOwnerMustBeAnObjectTheSolutionBrings() {
        SolutionBundle stray = new SolutionBundle("demo", "demo-feature", "Label", "", good().application(),
                List.of("Widget"), List.of("Gadget"), good().policies());
        SolutionBundle fine = new SolutionBundle("demo", "demo-feature", "Label", "", good().application(),
                List.of("Widget"), List.of("Widget"), good().policies());

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(stray), WIDGET, null))
                .hasMessageContaining("solution 'demo'").hasMessageContaining("the sheet owner 'Gadget'");
        assertThatCode(() -> SolutionValidator.validate(List.of(fine), WIDGET, null)).doesNotThrowAnyException();
    }

    @Test
    void twoPoliciesOfOneSolutionMayNotShareAName() {
        SolutionBundle twins = bundle("demo", "demo-feature", "demo", List.of("Widget"), good().application().entries(),
                List.of(new SolutionBundle.Policy("a", "Reader", "d", Map.of()),
                        new SolutionBundle.Policy("b", "reader", "d", Map.of())));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(twins), WIDGET, null))
                .hasMessageContaining("the name is used by another policy of the solution");
    }

    // ---- several applications, Home first and placeholders (FA9, ADR-0203) ----

    private static SolutionBundle.Entry home() {
        return new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Home", "", "home", "");
    }

    private static SolutionBundle.Entry soon(String pageKey) {
        return new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Soon", "", pageKey, "", true);
    }

    private static SolutionBundle.Application application(String name, SolutionBundle.Entry... entries) {
        return new SolutionBundle.Application(name, "App", "", "Group", List.of(entries));
    }

    private static SolutionBundle several(SolutionBundle.Application... applications) {
        return new SolutionBundle("many", "many-feature", "Label", "", List.of(applications), List.of(), List.of(),
                List.of());
    }

    @Test
    void aBundleWithSeveralPlaceholderApplicationsAndNoObjectsOrPoliciesPasses() {
        SolutionBundle bundle = several(application("one", home(), soon("a"), soon("b")),
                application("two", home(), soon("a")));

        assertThatCode(() -> SolutionValidator.validate(List.of(bundle), platform(), Set.of("many-feature")))
                .doesNotThrowAnyException();
    }

    @Test
    void twoApplicationsOfOneBundleMayNotShareAName() {
        SolutionBundle twins = several(application("one", home()), application("one", home()));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(twins), platform(), null))
                .hasMessageContaining("used by another solution or twice in this one");
    }

    @Test
    void twoEntriesOfOneApplicationMayNotShareAnIdentity() {
        SolutionBundle twins = several(application("one", home(), soon("a"), soon("a")));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(twins), platform(), null))
                .hasMessageContaining("entries[2]: the identity is used by another entry of the application");
    }

    @Test
    void theSameIdentityInTwoApplicationsIsFine() {
        SolutionBundle bundle = several(application("one", home(), soon("a")), application("two", home(), soon("a")));

        assertThatCode(() -> SolutionValidator.validate(List.of(bundle), platform(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void everyApplicationOpensOnHome() {
        SolutionBundle notFirst = several(application("one", soon("a"), home()));
        SolutionBundle none = several(application("one", soon("a")));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(notFirst), platform(), null))
                .hasMessageContaining("the first entry is the Home page");
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(none), platform(), null))
                .hasMessageContaining("application 'one'").hasMessageContaining("the first entry is the Home page");
    }

    @Test
    void aPlaceholderMayNameNoObject() {
        SolutionBundle.Entry reading = new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Soon", "", "a",
                "Widget", true);
        SolutionBundle bundle = new SolutionBundle("demo", "demo-feature", "Label", "",
                application("demo", home(), reading), List.of("Widget"), List.of());

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(bundle), WIDGET, null))
                .hasMessageContaining("a placeholder names no object");
    }

    // ---- the rights on fields (FA4, ADR-0189) ----

    private static ObjectDefinition widgetWithFields() {
        app.platform.metadata.FieldDefinition name = new app.platform.metadata.FieldDefinition("Widget", "name", "Name",
                "", DefinitionKind.STANDARD, app.platform.metadata.FieldType.TEXT, false, false, null,
                new app.platform.metadata.FieldConfiguration.TextConfiguration(80), false, 0);
        app.platform.metadata.FieldDefinition gone = new app.platform.metadata.FieldDefinition("Widget", "old", "Old",
                "", DefinitionKind.STANDARD, app.platform.metadata.FieldType.TEXT, false, false, null,
                new app.platform.metadata.FieldConfiguration.TextConfiguration(80), true, 0);
        app.platform.metadata.FieldDefinition system = new app.platform.metadata.FieldDefinition("Widget", "createdAt",
                "Created", "", DefinitionKind.SYSTEM, app.platform.metadata.FieldType.DATETIME, false, false, null,
                new app.platform.metadata.FieldConfiguration.NoConfiguration(), false, 0);
        return new ObjectDefinition("Widget", "Widget", "Widgets", "", DefinitionKind.STANDARD, null, false, 0,
                List.of(name, gone, system), "demo-feature", true);
    }

    private static SolutionBundle withFieldRights(String key) {
        return bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Overview", "", "home",
                        "Widget")),
                List.of(new SolutionBundle.Policy("reader", "Reader", "d", Map.of("Widget",
                        Set.of(ObjectAction.READ)), Set.of(), Map.of(key,
                                Set.of(app.platform.security.FieldAction.READ)))));
    }

    @Test
    void aRightOnAFieldTheObjectHasPasses() {
        assertThatCode(() -> SolutionValidator.validate(List.of(withFieldRights("Widget.name")),
                platform(widgetWithFields()), null)).doesNotThrowAnyException();
    }

    @Test
    void aRightOnAFieldTheObjectDoesNotHaveIsRefusedAndNamesIt() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(withFieldRights("Widget.missing")),
                platform(widgetWithFields()), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accessPolicies 'reader'").hasMessageContaining("'Widget.missing'")
                .hasMessageContaining("does not have");
    }

    @Test
    void aRightOnAFieldNoLongerInUseIsRefusedButASystemFieldMayBeNamed() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(withFieldRights("Widget.old")),
                platform(widgetWithFields()), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer in use");
        assertThatCode(() -> SolutionValidator.validate(List.of(withFieldRights("Widget.createdAt")),
                platform(widgetWithFields()), null)).doesNotThrowAnyException();
    }

    @Test
    void aRightOnAFieldOfAnObjectThePolicyGivesNoRightOnIsRefused() {
        SolutionBundle bundle = bundle("demo", "demo-feature", "demo", List.of("Widget"),
                List.of(new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Overview", "", "home",
                        "Widget")),
                List.of(new SolutionBundle.Policy("reader", "Reader", "d", Map.of(), Set.of(),
                        Map.of("Widget.name", Set.of(app.platform.security.FieldAction.READ)))));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(bundle), platform(widgetWithFields()), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gives no right");
    }

    // ---- the parts of an application (FA13B, ADR-0226) ----

    private static SolutionBundle withRoles(String key, String feature, String application, Map<String, String> roles) {
        return new SolutionBundle(key, feature, "Label", "", List.of(new SolutionBundle.Application(application,
                "App", "", good().application().entries())), List.of("Widget", "Gadget"), List.of(), List.of(),
                List.of(), 0, roles);
    }

    private static final PlatformObjects TWO = platform(object("Widget", "demo-feature", true),
            object("Gadget", "demo-feature", true));

    @Test
    void aSolutionThatDeclaresItsPartsPasses() {
        SolutionBundle bundle = withRoles("demo", "demo-feature", "demo", Map.of("first", "Widget",
                "second", "Gadget"));

        assertThatCode(() -> SolutionValidator.validate(List.of(bundle), TWO, null)).doesNotThrowAnyException();
    }

    @Test
    void twoSolutionsMayDeclareTheSamePartsButNotClaimOneObjectInAnotherPartOrWithOtherObjects() {
        SolutionBundle first = withRoles("demo", "demo-feature", "demo", Map.of("first", "Widget",
                "second", "Gadget"));
        SolutionBundle same = withRoles("copy", "copy-feature", "copy", Map.of("first", "Widget", "second", "Gadget"));
        SolutionBundle otherPart = withRoles("other", "other-feature", "other", Map.of("second", "Widget"));
        SolutionBundle otherCombination = withRoles("third", "third-feature", "third", Map.of("first", "Widget"));

        assertThatCode(() -> SolutionValidator.checkRoles(List.of(first, same))).doesNotThrowAnyException();
        assertThatThrownBy(() -> SolutionValidator.checkRoles(List.of(first, otherPart)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("solution 'other'")
                .hasMessageContaining("'Widget' is claimed by solution 'demo'");
        assertThatThrownBy(() -> SolutionValidator.checkRoles(List.of(first, otherCombination)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'Widget' is claimed");
    }
}
