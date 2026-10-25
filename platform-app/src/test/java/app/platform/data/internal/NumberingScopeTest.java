package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** Counter identity is independent of presentation and distinguishes field names, not only their values. */
class NumberingScopeTest {

    private static NumberSeriesCatalogue.Series series(String format) {
        return new NumberSeriesCatalogue.Series("doc", "Doc", "number", "kind",
                Map.of("WI", NumberFormat.parse(format)), Map.of(), "");
    }

    @Test
    void aChangedOrderWidthOrExplicitSelectorKeepsTheOriginalCounterKey() {
        NumberSeriesCatalogue.Series series = series("WI/{field:area}/{field:part}/{seq:2}");
        Map<String, Object> values = Map.of("kind", "WI", "area", "PRD", "part", "AB");
        for (String text : List.of("{field:part}/{field:area}/{seq:1}",
                "{field:kind}/{field:area}/{field:part}/{seq:3}", "X/{field:area}/{field:part}/{seq:1}")) {
            assertThat(NumberingFormats.scope(series, NumberFormat.parse(text), values)).isEqualTo("WI/PRD/AB");
        }
    }

    @Test
    void twoDifferentFieldsWithIdenticalValuesNeverAccidentallyShareACounter() {
        NumberSeriesCatalogue.Series series = series("WI/{field:area}/{seq:2}");
        Map<String, Object> values = Map.of("kind", "WI", "area", "A", "part", "A");
        String original = NumberingFormats.scope(series, NumberFormat.parse("WI/{field:area}/{seq:1}"), values);
        String changed = NumberingFormats.scope(series, NumberFormat.parse("WI/{field:part}/{seq:1}"), values);
        assertThat(original).isEqualTo("WI/A");
        assertThat(changed).isNotEqualTo(original).contains("/part/A");
    }

    @Test
    void newScopesUseSortedFieldNamesAndCannotOverlapAnOldCodePrefix() {
        NumberSeriesCatalogue.Series series = series("WI/{seq:1}");
        Map<String, Object> values = Map.of("kind", "WI", "area", "PRD", "part", "AB");
        String first = NumberingFormats.scope(series, NumberFormat.parse("{field:area}/{field:part}/{seq:2}"), values);
        String reordered = NumberingFormats.scope(series,
                NumberFormat.parse("{field:part}/{field:area}/{seq:3}"), values);
        assertThat(first).isEqualTo(reordered).startsWith("numbering-scope-v2/");
        assertThat("numbering-scope-v2").hasSizeGreaterThan(10);
    }

    @Test
    void fallbacksAlwaysIncludeTheirIdentityFieldsAndOneCounter() {
        NumberSeriesCatalogue.checkDefault(NumberFormat.parse("{field:kind}/{field:area}/{seq:1}"),
                "kind", "area", true);
        NumberSeriesCatalogue.checkDefault(NumberFormat.parse("{field:kind}/{seq:1}"), "kind", "area", false);
        assertThatThrownBy(() -> NumberSeriesCatalogue.checkDefault(NumberFormat.parse("{field:area}/{seq:1}"),
                "kind", "area", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumberSeriesCatalogue.checkDefault(NumberFormat.parse("{field:kind}"),
                "kind", "area", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 100)
    void digitWidthsNeverCutCountsAndTheFormatRoundTrips(@ForAll @IntRange(min = 1, max = 9) int width,
            @ForAll @LongRange(min = 1, max = 999999999999L) long count) {
        NumberFormat format = NumberFormat.parse("{field:kind}/{seq:" + width + "}");
        String rendered = format.render(Map.of("kind", "WI"), count);
        assertThat(Long.parseLong(rendered.substring(3))).isEqualTo(count);
        assertThat(NumberFormat.parse(format.text()).render(Map.of("kind", "WI"), count)).isEqualTo(rendered);
    }
}
