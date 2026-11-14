package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What stops the start when a number series is declared wrongly (FA2A, ADR-0184): every message names the series and
 * the
 * part, none repeats a text, and the right declaration is accepted.
 */
class NumberSeriesCatalogueTest {

    private static FieldDefinition field(String name, FieldType type, FieldConfiguration configuration,
            boolean unique, boolean codeValues, boolean platformWritten) {
        return new FieldDefinition("Doc", name, name, "", DefinitionKind.STANDARD, type, false, unique, null,
                configuration, false, 0, false, false, false, false, codeValues, platformWritten);
    }

    private static PicklistConfiguration codes(String... values) {
        List<PicklistValue> list = new ArrayList<>();
        for (String value : values) {
            list.add(new PicklistValue(value, value, true));
        }
        return new PicklistConfiguration(list);
    }

    private static FieldDefinition number() {
        return field("number", FieldType.TEXT, new TextConfiguration(40), true, false, true);
    }

    private static PlatformObjects platform(FieldDefinition... fields) {
        List<FieldDefinition> all = new ArrayList<>(List.of(fields));
        ObjectDefinition doc = new ObjectDefinition("Doc", "Doc", "Docs", "", DefinitionKind.STANDARD, null, false, 0,
                all);
        return new PlatformObjects() {
            @Override
            public List<ObjectDefinition> all() {
                return List.of(doc);
            }

            @Override
            public Optional<ObjectDefinition> named(String apiName) {
                return apiName.equals("Doc") ? Optional.of(doc) : Optional.empty();
            }
        };
    }

    private static PlatformObjects usual() {
        return platform(number(), field("kind", FieldType.PICKLIST, codes("WI", "IMS"), false, true, false),
                field("area", FieldType.PICKLIST, codes("PRD", "QLT"), false, true, false),
                field("note", FieldType.TEXT, new TextConfiguration(20), false, false, false),
                field("loose", FieldType.PICKLIST, codes("A"), false, false, false));
    }

    private static NumberSeriesDeclaration series(String selector, Map<String, String> formats) {
        return new NumberSeriesDeclaration("doc", "Doc", "number", selector, formats);
    }

    private static void refused(PlatformObjects platform, NumberSeriesDeclaration declaration, String... words) {
        assertThatThrownBy(() -> new NumberSeriesCatalogue(platform, List.of(declaration)))
                .isInstanceOf(IllegalStateException.class).satisfies(refusal -> {
                    assertThat(refusal.getMessage()).contains("doc");
                    for (String word : words) {
                        assertThat(refusal.getMessage()).contains(word);
                    }
                });
    }

    @Test
    void aSoundDeclarationIsAcceptedAndFindsItsFormatByTheValueOfTheSelector() {
        NumberSeriesCatalogue catalogue = new NumberSeriesCatalogue(usual(), List.of(series("kind",
                Map.of("WI", "WI/{field:area}/{seq:2}", "IMS", "IMS/{seq:2}"))));

        NumberSeriesCatalogue.Series found = catalogue.series("doc").orElseThrow();
        assertThat(found.formatFor("WI")).isPresent();
        assertThat(found.formatFor("IMS")).isPresent();
        assertThat(found.formatFor("FMEA")).isEmpty();
        assertThat(catalogue.series("other")).isEmpty();
    }

    @Test
    void aSeriesWithOneFormatNeedsNoSelector() {
        NumberSeriesCatalogue catalogue = new NumberSeriesCatalogue(usual(), List.of(series("",
                Map.of("", "DOC-{seq:4}"))));

        assertThat(catalogue.series("doc").orElseThrow().formatFor("")).isPresent();
    }

    @Test
    void noDeclarationsIsFine() {
        assertThat(new NumberSeriesCatalogue(usual(), List.of()).all()).isEmpty();
    }

    @Test
    void aSeriesDeclaredTwiceIsRefused() {
        NumberSeriesDeclaration one = series("", Map.of("", "DOC-{seq:4}"));

        assertThatThrownBy(() -> new NumberSeriesCatalogue(usual(), List.of(one, one)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("declared twice");
    }

    @Test
    void anObjectThePlatformDoesNotDefineIsRefused() {
        refused(usual(), new NumberSeriesDeclaration("doc", "Nothing", "number", "", Map.of("", "D-{seq:2}")),
                "object");
    }

    @Test
    void theFieldOfTheNumberMustBeAUniqueTextFieldOnlyThePlatformWrites() {
        refused(platform(field("number", FieldType.TEXT, new TextConfiguration(40), false, false, true)),
                series("", Map.of("", "D-{seq:2}")), "unique text field");
        refused(platform(field("number", FieldType.TEXT, new TextConfiguration(40), true, false, false)),
                series("", Map.of("", "D-{seq:2}")), "only the platform writes");
        refused(platform(field("number", FieldType.LONG_TEXT, new TextConfiguration(40), true, false, true)),
                series("", Map.of("", "D-{seq:2}")), "unique text field");
        refused(usual(), new NumberSeriesDeclaration("doc", "Doc", "absent", "", Map.of("", "D-{seq:2}")),
                "field of the number");
    }

    @Test
    void theFieldOfTheNumberMustBeLongEnoughForTheLongestNumber() {
        refused(platform(field("number", FieldType.TEXT, new TextConfiguration(10), true, false, true),
                field("area", FieldType.PICKLIST, codes("A"), false, true, false)),
                series("", Map.of("", "DOC/{field:area}/{seq:2}")), "shorter than the longest number");
    }

    @Test
    void theSelectorMustBeAShortCodePicklistAndEveryFormatBelongsToOneOfItsValues() {
        refused(usual(), series("loose", Map.of("A", "A-{seq:2}")), "short-code picklist");
        refused(usual(), series("note", Map.of("A", "A-{seq:2}")), "short-code picklist");
        refused(usual(), series("absent", Map.of("A", "A-{seq:2}")), "selector");
        refused(usual(), series("kind", Map.of("FMEA", "FMEA-{seq:2}")), "belongs to a value");
        refused(usual(), series("kind", Map.of("", "D-{seq:2}")), "belongs to a value");
    }

    @Test
    void aSeriesWithoutASelectorHasExactlyOneFormat() {
        refused(usual(), series("", Map.of("WI", "WI-{seq:2}")), "one format");
        refused(usual(), series("", Map.of()), "no format");
    }

    @Test
    void aFormatMayReadOnlyTextFieldsAndShortCodePicklistsOfTheObject() {
        refused(usual(), series("", Map.of("", "D/{field:absent}/{seq:2}")), "field the object does not have");
        refused(usual(), series("", Map.of("", "D/{field:loose}/{seq:2}")), "not a text field or a short-code");
        refused(usual(), series("", Map.of("", "D/{field:number}/{seq:2}")), "not a text field or a short-code");
    }

    @Test
    void aFormatOutsideTheLanguageIsRefusedWithThePartAndNeverTheText() {
        String bad = "<script>alert(1)</script>{seq:2}";

        assertThatThrownBy(() -> new NumberSeriesCatalogue(usual(), List.of(series("", Map.of("", bad)))))
                .isInstanceOf(IllegalStateException.class).satisfies(refusal -> {
                    assertThat(refusal.getMessage()).contains("doc").contains("format 1");
                    assertThat(refusal.getMessage()).doesNotContain("script").doesNotContain("alert");
                });
    }

    @Test
    void aKeyThatIsNotShapedLikeAKeyIsRefused() {
        assertThatThrownBy(() -> new NumberSeriesCatalogue(usual(), List.of(new NumberSeriesDeclaration("Bad Key",
                "Doc", "number", "", Map.of("", "D-{seq:2}"))))).isInstanceOf(IllegalStateException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("Bad Key"));
    }
}
