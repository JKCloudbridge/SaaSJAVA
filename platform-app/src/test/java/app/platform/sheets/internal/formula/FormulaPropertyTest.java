package app.platform.sheets.internal.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of the one canonical form of a formula over many generated formulas (SH2, ADR-0252): printing what was
 * parsed gives a text that parses to the same tree (a fixed point), and the canonical text does not depend on how a
 * formula was typed (blanks, the case of a function or a column, the spelling of a number). A hash that moved
 * with how a
 * formula was typed would make one sheet look like two.
 */
class FormulaPropertyTest {

    private static final FormulaLimits LIMITS = FormulaLimits.DEFAULTS;
    private static final FormulaTranslator.Layout LAYOUT = CaseFile.flat();

    private static final List<List<String>> NUMBERS = List.of(List.of("5", "5.0", "05", "5.00E0", "0.5E1"),
            List.of("0.5", ".5", "0.50", "5E-1", "00.5"), List.of("100", "1E2", "100.0", "0100"),
            List.of("2.675", "2.6750", "02.675"), List.of("0", "0.0", ".0", "00"));

    /** Makes a formula from a structure (the seed) and a way of typing it (the noise). */
    private static final class Typist {
        private final Random structure;
        private final Random noise;
        private final boolean decorate;

        Typist(long seed, long noise, boolean decorate) {
            this.structure = new Random(seed);
            this.noise = new Random(noise);
            this.decorate = decorate;
        }

        private String blank() {
            return decorate && noise.nextInt(3) == 0 ? " ".repeat(1 + noise.nextInt(3)) : "";
        }

        private String name(String upper) {
            return decorate && noise.nextBoolean() ? upper.toLowerCase(java.util.Locale.ROOT) : upper;
        }

        String expression(int depth) {
            int kind = structure.nextInt(depth <= 0 ? 4 : 11);
            switch (kind) {
                case 0: {
                    List<String> spellings = NUMBERS.get(structure.nextInt(NUMBERS.size()));
                    return blank() + (decorate ? spellings.get(noise.nextInt(spellings.size())) : spellings.get(0))
                            + blank();
                }
                case 1:
                    return blank() + "\"" + (structure.nextBoolean() ? "ab" : "  x  ") + "\"" + blank();
                case 2:
                    return blank() + reference() + blank();
                case 3:
                    return blank() + (structure.nextBoolean() ? "TRUE"
                            : name("FALSE").toUpperCase(java.util.Locale.ROOT))
                            + blank();
                case 4:
                    return blank() + name("SUM") + "(" + range() + ")" + blank();
                case 5:
                    return blank() + name("IF") + "(" + expression(depth - 1) + blank() + "<" + blank()
                            + expression(depth - 1) + "," + expression(depth - 1) + "," + expression(depth - 1) + ")";
                case 6:
                    return blank() + name("ROUND") + "(" + expression(depth - 1) + "," + expression(0) + ")";
                case 7:
                    return expression(depth - 1) + blank() + "+*-/^".charAt(structure.nextInt(5)) + blank()
                            + expression(depth - 1);
                case 8:
                    return blank() + "(" + expression(depth - 1) + ")" + blank();
                case 9:
                    return blank() + "-" + blank() + reference() + blank() + "%";
                default:
                    return blank() + name("AND") + "(" + expression(depth - 1) + "=" + expression(depth - 1) + ","
                            + expression(0) + ")";
            }
        }

        private String reference() {
            String column = String.valueOf((char) ('A' + structure.nextInt(4)));
            int row = 1 + structure.nextInt(3);
            boolean absoluteColumn = structure.nextInt(4) == 0;
            boolean absoluteRow = structure.nextInt(4) == 0;
            String text = (absoluteColumn ? "$" : "") + column + (absoluteRow ? "$" : "") + row;
            return decorate && noise.nextBoolean() ? text.toLowerCase(java.util.Locale.ROOT) : text;
        }

        private String range() {
            return reference() + ":" + reference();
        }
    }

    private static String canonicalOf(String typed) {
        return FormulaPrinter.print(FormulaParser.parse(FormulaTranslator.toIds(typed, 1, LAYOUT), LIMITS));
    }

    @Property(tries = 400)
    void theCanonicalTextIsAFixedPoint(@ForAll @LongRange(min = 1, max = 5_000_000) long seed) {
        String typed = new Typist(seed, seed, true).expression(4);
        String canonical = canonicalOf(typed);

        assertThat(FormulaPrinter.canonical(canonical, LIMITS)).isEqualTo(canonical);
        assertThat(canonical).isEqualTo(canonical.strip());
    }

    @Property(tries = 400)
    void theCanonicalTextDoesNotDependOnHowTheFormulaWasTyped(
            @ForAll @LongRange(min = 1, max = 5_000_000) long seed) {
        String plain = canonicalOf(new Typist(seed, 1, false).expression(4));
        for (long noise = 1; noise <= 3; noise++) {
            String decorated = canonicalOf(new Typist(seed, seed * 7 + noise, true).expression(4));
            assertThat(decorated).isEqualTo(plain);
        }
    }

    @Property(tries = 200)
    void aFormulaDrawnBackAndTranslatedAgainIsTheSameStoredText(
            @ForAll @LongRange(min = 1, max = 5_000_000) long seed) {
        String stored = canonicalOf(new Typist(seed, seed, true).expression(3));
        String drawn = FormulaTranslator.toExcel(stored, 1, LAYOUT);

        assertThat(canonicalOf(drawn)).isEqualTo(stored);
    }
}
