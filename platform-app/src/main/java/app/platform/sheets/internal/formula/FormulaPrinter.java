package app.platform.sheets.internal.formula;

import app.platform.sheets.internal.formula.Node.Binary;
import app.platform.sheets.internal.formula.Node.Bool;
import app.platform.sheets.internal.formula.Node.Call;
import app.platform.sheets.internal.formula.Node.Group;
import app.platform.sheets.internal.formula.Node.Negate;
import app.platform.sheets.internal.formula.Node.Num;
import app.platform.sheets.internal.formula.Node.Percent;
import app.platform.sheets.internal.formula.Node.Range;
import app.platform.sheets.internal.formula.Node.Ref;
import app.platform.sheets.internal.formula.Node.Text;

/**
 * Prints a tree as the one <em>canonical</em> stored text of a formula (SH2, ADR-0252): function names in capitals, no
 * blank outside a text constant, one spelling of each number, TRUE and FALSE in capitals, the parts of a reference
 * token in a fixed order, and the brackets the author wrote. Printing what was parsed gives a text that parses to the
 * same tree, so the canonical text is a fixed point and the hash of a sheet does not depend on how a formula was typed.
 */
public final class FormulaPrinter {

    private FormulaPrinter() {
    }

    /** The canonical text of a tree. */
    public static String print(Node node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** The canonical text of a formula in its stored form, or the refusal. */
    public static String canonical(String stored, FormulaLimits limits) {
        return print(FormulaParser.parse(stored, limits));
    }

    private static void write(Node node, StringBuilder out) {
        switch (node) {
            case Num n -> out.append(n.text());
            case Text t -> out.append('"').append(t.value().replace("\"", "\"\"")).append('"');
            case Bool b -> out.append(b.value() ? "TRUE" : "FALSE");
            case Ref r -> token(r, out);
            case Range r -> {
                token(r.from(), out);
                out.append(':');
                token(r.to(), out);
            }
            case Negate n -> {
                out.append('-');
                write(n.operand(), out);
            }
            case Percent p -> {
                write(p.operand(), out);
                out.append('%');
            }
            case Binary b -> {
                write(b.left(), out);
                out.append(b.operator());
                write(b.right(), out);
            }
            case Group g -> {
                out.append('(');
                write(g.inner(), out);
                out.append(')');
            }
            case Call c -> {
                out.append(c.name()).append('(');
                for (int i = 0; i < c.arguments().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(c.arguments().get(i), out);
                }
                out.append(')');
            }
        }
    }

    /** One reference token in its one form. */
    public static void token(Ref ref, StringBuilder out) {
        out.append("@{").append(ref.column());
        if (ref.growing()) {
            out.append(",*");
        } else if (ref.row() != null) {
            out.append(',').append(ref.row());
            if (ref.absoluteColumn() || ref.absoluteRow()) {
                out.append(',');
                if (ref.absoluteColumn()) {
                    out.append('c');
                }
                if (ref.absoluteRow()) {
                    out.append('r');
                }
            }
        } else if (ref.absoluteColumn()) {
            out.append(",,c");
        }
        out.append('}');
    }
}
