package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Where an application stands in the grid and how one solution uses the objects and access policies another brings
 * (Functional track FA13A, ADR-0225). The order is data (a number, lowest first, ties by key); sharing needs both sides
 * to say so, so no object is ever reachable through a solution the object's own file does not name.
 */
class SolutionOrderAndSharingTest {

    private static final String FIRST = """
            key: %s
            feature: %s
            label: Demo
            %s
            application:
              apiName: %s
              label: Demo
              entries:
                - { type: PLATFORM, label: Home, pageKey: home }
            """;

    private static Path write(Path dir, String key, String order) throws IOException {
        Path file = dir.resolve(key).resolve("solution.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, FIRST.formatted(key, key + "-feature", order, key));
        return dir;
    }

    private static List<SolutionBundle> load(Path dir) {
        return SolutionLoader.load("file:" + dir.toUri().getPath() + "*/solution.yml");
    }

    // ---- the order ----

    @Test
    void theApplicationsThatAskForAPlaceComeFirstAndTheRestKeepTheirOrder(@TempDir Path dir) throws IOException {
        write(dir, "alpha", "");
        write(dir, "zulu", "order: 1");
        write(dir, "mike", "order: 3");
        write(dir, "bravo", "");
        write(dir, "yankee", "order: 2");

        assertThat(load(dir)).extracting(SolutionBundle::key)
                .containsExactly("zulu", "yankee", "mike", "alpha", "bravo");
    }

    @Test
    void twoThatAskForTheSamePlaceStandInTheOrderOfTheirKeys(@TempDir Path dir) throws IOException {
        write(dir, "beta", "order: 2");
        write(dir, "alpha", "order: 2");
        write(dir, "gamma", "order: 1");

        assertThat(load(dir)).extracting(SolutionBundle::key).containsExactly("gamma", "alpha", "beta");
    }

    @ParameterizedTest(name = "order: {0}")
    @CsvSource({"0", "-1", "1000", "'one'", "1.5", "true"})
    void aPlaceThatIsNotAWholeNumberFromOneToTheLargestIsRefused(String order, @TempDir Path dir) throws IOException {
        write(dir, "demo", "order: " + order);

        assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solution 'demo'").hasMessageContaining("'order'");
    }

    @Test
    void theRealSolutionsStandInTheOrderTheGridShows() {
        List<String> keys = SolutionLoader.load().stream().map(SolutionBundle::key).toList();

        assertThat(keys.subList(0, 5)).containsExactly("document-management", "capa", "audit-management",
                "supplier-management", "management-core");
        // Place 6 is Environment Management (FA13B); the bundles that never asked stand after, by key.
        assertThat(keys.get(5)).isEqualTo("environment-management");
        assertThat(keys.subList(6, keys.size())).containsExactly("inspection", "processes");
        assertThat(SolutionLoader.load()).filteredOn(bundle -> bundle.order() > 0)
                .extracting(SolutionBundle::order).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void everyRealApplicationOpensOnHomeAndTheNewTilesAreAnnounced() {
        SolutionLoader.load().forEach(bundle -> bundle.applications().forEach(application -> {
            SolutionBundle.Entry first = application.entries().get(0);
            assertThat(first.kind()).isEqualTo(ShippedEntryView.Kind.PLATFORM);
            assertThat(first.pageKey()).as(application.apiName()).isEqualTo("home");
        }));
        SolutionBundle capa = real("capa");
        SolutionBundle supplier = real("supplier-management");
        SolutionBundle core = real("management-core");
        assertThat(capa.application().entries()).extracting(SolutionBundle.Entry::label).containsExactly("Home");
        assertThat(real("audit-management").application().entries()).hasSize(1);
        assertThat(supplier.application().entries()).extracting(SolutionBundle.Entry::label)
                .containsExactly("Home", "Supplier Performance Monitoring", "Supplier Portal");
        // Management Core is a real application since MC1 (ADR-0257): five working tabs, three announced ones.
        assertThat(core.application().entries().stream()
                .filter(entry -> entry.kind() == ShippedEntryView.Kind.PLATFORM))
                .extracting(SolutionBundle.Entry::label).containsExactly("Home",
                        "Document Control", "Change requests", "Record Control", "Report", "KPI management",
                        "Management Review", "Plantwise Review");
        assertThat(core.application().entries().stream().filter(SolutionBundle.Entry::placeholder))
                .extracting(SolutionBundle.Entry::label)
                .containsExactly("KPI management", "Management Review", "Plantwise Review");
        // Its documents are Document Management's, chosen by their number (MC1.1, ADR-0259): it brings no object of
        // its own, uses the shared ones, and says so in the one option the generic code reads.
        assertThat(core.objects()).isEmpty();
        assertThat(core.policies()).isEmpty();
        assertThat(core.uses()).containsExactly("InspectionTemplate", "InspectionFill", "InspectionChangeRequest",
                "DepartmentGroup");
        assertThat(core.roles()).isEqualTo(real("document-management").roles());
        assertThat(core.options()).containsEntry("routedByNumber", "true");
        for (SolutionBundle bundle : List.of(capa, supplier, real("audit-management"))) {
            assertThat(bundle.objects()).isEmpty();
            assertThat(bundle.policies()).isEmpty();
            assertThat(bundle.application().entries().stream().skip(1))
                    .allMatch(SolutionBundle.Entry::placeholder);
        }
    }

    @Test
    void documentManagementShowsTheSameTabsAsTheQualitySystemAndOwnsNothing() {
        SolutionBundle documents = real("document-management");
        SolutionBundle quality = real("inspection");

        assertThat(documents.application().entries()).isEqualTo(quality.application().entries());
        assertThat(documents.objects()).isEmpty();
        assertThat(documents.policies()).isEmpty();
        assertThat(documents.uses()).containsExactlyElementsOf(quality.objects());
        assertThat(documents.feature()).isNotEqualTo(quality.feature());
        // The department setup is shared by Environment Management too (FA13B, ADR-0226); the other policies are not.
        assertThat(quality.policies()).allMatch(policy -> policy.alsoAvailableWith().contains(documents.feature()));
        assertThat(quality.policies().stream().filter(policy -> policy.alsoAvailableWith()
                .contains("environment-management")).map(SolutionBundle.Policy::key)).containsExactly("departments");
        // Both applications declare the very same parts, because Document Management shows the same records.
        assertThat(documents.roles()).isEqualTo(quality.roles()).containsKeys("template", "fill", "changeRequest");
    }

    private static SolutionBundle real(String key) {
        return SolutionLoader.load().stream().filter(bundle -> bundle.key().equals(key)).findFirst().orElseThrow();
    }

    // ---- sharing: the shape of a file ----

    @Test
    void aFileThatUsesObjectsAndSharesPoliciesLoads(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("demo").resolve("solution.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                key: demo
                feature: demo-feature
                label: Demo
                uses: [Widget]
                application:
                  apiName: demo
                  label: Demo
                  entries:
                    - { type: PLATFORM, label: Home, pageKey: home }
                accessPolicies:
                  - key: reader
                    name: Reader
                    description: Reads.
                    alsoAvailableWith: [other-feature]
                    objects:
                      Widget: [read]
                """);

        SolutionBundle bundle = load(dir).get(0);

        assertThat(bundle.uses()).containsExactly("Widget");
        assertThat(bundle.order()).isZero();
        assertThat(bundle.policies().get(0).alsoAvailableWith()).containsExactly("other-feature");
    }

    static Stream<Arguments> sharingMistakes() {
        String policy = """
                accessPolicies:
                  - key: r
                    name: R
                    description: D
                """;
        return Stream.of(
                Arguments.of("an object used that is not shaped like one", "uses: [widget]\n",
                        "'uses' lists API names"),
                Arguments.of("an object used twice", "uses: [Widget, Widget]\n",
                        "listed twice or is both brought and used"),
                Arguments.of("an object both brought and used", "objects: [Widget]\nuses: [Widget]\n",
                        "listed twice or is both brought and used"),
                Arguments.of("a policy feature that is not a key",
                        policy + "    alsoAvailableWith: [Not A Key]\n",
                        "'alsoAvailableWith' lists plan feature keys"),
                Arguments.of("a policy feature twice", policy + "    alsoAvailableWith: [a-b, a-b]\n",
                        "'alsoAvailableWith' lists plan feature keys"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharingMistakes")
    void aMistakeInASharingKeyStopsTheLoading(String name, String extra, String expected, @TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("demo").resolve("solution.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                key: demo
                feature: demo-feature
                label: Demo
                application:
                  apiName: demo
                  label: Demo
                  entries:
                    - { type: PLATFORM, label: Home, pageKey: home }
                """ + extra);

        assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solution 'demo'").hasMessageContaining(expected);
    }

    // ---- sharing: against the rest of the platform ----

    private static ObjectDefinition object(String name, String feature, String... also) {
        return new ObjectDefinition(name, name, name + "s", "", DefinitionKind.STANDARD, null, false, 0, List.of(),
                feature, false, null, List.of(also));
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

    private static SolutionBundle.Entry home(String object) {
        return new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Home", "", "home", object);
    }

    private static SolutionBundle owner(List<SolutionBundle.Policy> policies) {
        return new SolutionBundle("owner", "owner-feature", "Owner", "", List.of(new SolutionBundle.Application(
                "owner", "Owner", "", List.of(home("Widget")))), List.of("Widget"), List.of(), policies);
    }

    private static SolutionBundle user(List<String> uses, SolutionBundle.Entry entry) {
        return new SolutionBundle("user", "user-feature", "User", "", List.of(new SolutionBundle.Application(
                "user", "User", "", List.of(entry))), List.of(), List.of(), List.of(), uses, 0);
    }

    private static final SolutionBundle.Policy SHARED = new SolutionBundle.Policy("reader", "Reader", "d",
            Map.of("Widget", Set.of(ObjectAction.READ)), Set.of(), Map.of(), Set.of("user-feature"));

    private static final PlatformObjects SHARED_WIDGET = platform(object("Widget", "owner-feature", "user-feature"));

    @Test
    void anObjectOneSolutionBringsAndAnotherUsesPassesWhenBothSidesSayIt() {
        assertThatCode(() -> SolutionValidator.validate(List.of(owner(List.of(SHARED)),
                user(List.of("Widget"), home("Widget"))), SHARED_WIDGET, null)).doesNotThrowAnyException();
    }

    @Test
    void usingAnObjectWhoseFileDoesNotNameTheFeatureIsRefused() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of()),
                user(List.of("Widget"), home("Widget"))), platform(object("Widget", "owner-feature")), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("solution 'user'")
                .hasMessageContaining("does not list the solution's feature");
    }

    @Test
    void anObjectThatNamesAFeatureOfASolutionThatDoesNotUseItIsRefused() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of()),
                user(List.of(), home(""))), SHARED_WIDGET, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'Widget'").hasMessageContaining("does not use it");
    }

    @Test
    void usingAnObjectNoSolutionBringsIsRefused() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of()),
                user(List.of("Gadget"), home(""))), platform(object("Widget", "owner-feature"),
                        object("Gadget", null, "user-feature")), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which no other solution brings");
    }

    @Test
    void anEntryOfTheUsingSolutionMayReadAnObjectItUsesButNoOtherOne() {
        PlatformObjects both = platform(object("Widget", "owner-feature", "user-feature"),
                object("Gadget", "gadget-feature"));
        SolutionBundle gadget = new SolutionBundle("gadget", "gadget-feature", "G", "", List.of(
                new SolutionBundle.Application("gadget", "G", "", List.of(home("Gadget")))), List.of("Gadget"),
                List.of(), List.of());

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of(SHARED)),
                user(List.of("Widget"), home("Gadget")), gadget), both, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("solution 'user'")
                .hasMessageContaining("names an object the solution does not bring");
    }

    @Test
    void aPlaceholderStillNamesNoObjectAndTheApplicationNameStillIsUnique() {
        SolutionBundle.Entry placeholder = new SolutionBundle.Entry(ShippedEntryView.Kind.PLATFORM, "Soon", "",
                "soon", "Widget", true);
        SolutionBundle second = new SolutionBundle("user", "user-feature", "User", "", List.of(
                new SolutionBundle.Application("owner", "User", "", List.of(home("")))), List.of(), List.of(),
                List.of(), List.of("Widget"), 0);

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of(SHARED)), user(List.of("Widget"),
                placeholder)), SHARED_WIDGET, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a placeholder names no object");
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of(SHARED)), second), SHARED_WIDGET,
                null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the application name is used by another solution");
    }

    @Test
    void aSharedPolicyNeverGivesARightOnAnObjectTheOtherSolutionLacks() {
        SolutionBundle.Policy wide = new SolutionBundle.Policy("wide", "Wide", "d",
                Map.of("Widget", Set.of(ObjectAction.READ), "Gadget", Set.of(ObjectAction.READ)), Set.of(),
                Map.of(), Set.of("user-feature"));
        PlatformObjects both = platform(object("Widget", "owner-feature", "user-feature"),
                object("Gadget", "owner-feature"));
        SolutionBundle owner = new SolutionBundle("owner", "owner-feature", "Owner", "", List.of(
                new SolutionBundle.Application("owner", "Owner", "", List.of(home("Widget")))),
                List.of("Widget", "Gadget"), List.of(), List.of(wide));

        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner, user(List.of("Widget"), home("Widget"))),
                both, null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("accessPolicies 'wide'")
                .hasMessageContaining("does not have 'Gadget'");
    }

    @Test
    void aPolicyCannotBeSharedWithItsOwnSolutionOrAnUnknownFeature() {
        for (String feature : new String[] {"owner-feature", "nobody-feature"}) {
            SolutionBundle.Policy own = new SolutionBundle.Policy("reader", "Reader", "d",
                    Map.of("Widget", Set.of(ObjectAction.READ)), Set.of(), Map.of(), Set.of(feature));

            assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of(own)),
                    user(List.of("Widget"), home("Widget"))), SHARED_WIDGET, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("names a feature that is not another solution's");
        }
    }

    @Test
    void anObjectOfOneSolutionStillMustRequireItsFeatureAndOtherRulesStand() {
        assertThatThrownBy(() -> SolutionValidator.validate(List.of(owner(List.of())),
                platform(object("Widget", "someone-feature")), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not require the solution's feature");
    }
}
