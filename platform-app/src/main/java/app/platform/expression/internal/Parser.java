package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import app.platform.expression.Limits;
import app.platform.expression.internal.Lexer.Token;
import app.platform.expression.internal.Lexer.Type;
import app.platform.expression.internal.Node.BinaryOp;
import app.platform.expression.internal.Node.UnaryOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A recursive descent parser for the one grammar of the language (ADR-0076), from the loosest operator to the
 * tightest:
 *
 * <pre>
 * or         = and { OR and }
 * and        = not { AND not }
 * not        = NOT not | comparison
 * comparison = sum [ ( = | != | &lt; | &lt;= | &gt; | &gt;= ) sum ]        (not chained)
 * sum        = product { ( + | - ) product }
 * product    = unary { ( * | / | % ) unary }
 * unary      = - unary | primary
 * primary    = number | 'text' | TRUE | FALSE | NULL | name | name ( [ or { , or } ] ) | ( or )
 * </pre>
 *
 * <p>Two bounds keep it safe on any input: nesting (brackets, arguments and prefix operators) is counted and refused
 * past the limit, so the recursion is never deeper than a few hundred frames; and the parts are counted as they are
 * made and refused past the limit, so a hostile text cannot make a large tree. Chains of operators are loops, not
 * recursion. The parser decides only what is grammatical: whether a name is a field and whether a function exists is
 * the checker's question.
 */
final class Parser {

    private final String source;
    private final List<Token> tokens;
    private final Limits limits;
    private int index;
    private int nodes;

    Parser(String source, List<Token> tokens, Limits limits) {
        this.source = source;
        this.tokens = tokens;
        this.limits = limits;
    }

    /** The tree of the whole text. */
    Node parse() {
        if (peek().type() == Type.END) {
            throw fail(Kind.SYNTAX, peek().offset(), "The expression is empty: write a value, a field or a "
                    + "condition.");
        }
        Node root = or(0);
        if (peek().type() != Type.END) {
            throw unexpected(peek());
        }
        return root;
    }

    private Node or(int depth) {
        enter(depth);
        Node left = and(depth);
        while (isKeyword(peek(), "OR")) {
            Token operator = next();
            left = count(new Node.Binary(BinaryOp.OR, left, and(depth), operator.offset()));
        }
        return left;
    }

    private Node and(int depth) {
        Node left = not(depth);
        while (isKeyword(peek(), "AND")) {
            Token operator = next();
            left = count(new Node.Binary(BinaryOp.AND, left, not(depth), operator.offset()));
        }
        return left;
    }

    private Node not(int depth) {
        if (isKeyword(peek(), "NOT")) {
            Token operator = next();
            enter(depth + 1);
            return count(new Node.Unary(UnaryOp.NOT, not(depth + 1), operator.offset()));
        }
        return comparison(depth);
    }

    private Node comparison(int depth) {
        Node left = sum(depth);
        BinaryOp op = comparisonOf(peek().type());
        if (op == null) {
            return left;
        }
        Token operator = next();
        Node result = count(new Node.Binary(op, left, sum(depth), operator.offset()));
        if (comparisonOf(peek().type()) != null) {
            throw fail(Kind.SYNTAX, peek().offset(), "Comparisons cannot be chained: combine two comparisons with AND "
                    + "or OR.");
        }
        return result;
    }

    private Node sum(int depth) {
        Node left = product(depth);
        while (peek().type() == Type.PLUS || peek().type() == Type.MINUS) {
            Token operator = next();
            BinaryOp op = operator.type() == Type.PLUS ? BinaryOp.ADD : BinaryOp.SUBTRACT;
            left = count(new Node.Binary(op, left, product(depth), operator.offset()));
        }
        return left;
    }

    private Node product(int depth) {
        Node left = unary(depth);
        while (peek().type() == Type.STAR || peek().type() == Type.SLASH || peek().type() == Type.PERCENT) {
            Token operator = next();
            BinaryOp op = switch (operator.type()) {
                case STAR -> BinaryOp.MULTIPLY;
                case SLASH -> BinaryOp.DIVIDE;
                default -> BinaryOp.REMAINDER;
            };
            left = count(new Node.Binary(op, left, unary(depth), operator.offset()));
        }
        return left;
    }

    private Node unary(int depth) {
        if (peek().type() == Type.MINUS) {
            Token operator = next();
            enter(depth + 1);
            return count(new Node.Unary(UnaryOp.NEGATE, unary(depth + 1), operator.offset()));
        }
        return primary(depth);
    }

    private Node primary(int depth) {
        Token token = next();
        switch (token.type()) {
            case NUMBER -> {
                return count(new Node.Literal(ExpressionType.NUMBER, token.value(), token.offset()));
            }
            case TEXT -> {
                Object value = token.text().isEmpty() ? null : token.text();
                return count(new Node.Literal(ExpressionType.TEXT, value, token.offset()));
            }
            case LPAREN -> {
                Node inner = or(depth + 1);
                expect(Type.RPAREN, "A closing bracket is missing here.");
                return inner;
            }
            case NAME -> {
                return name(token, depth);
            }
            default -> throw missingValue(token);
        }
    }

    private Node name(Token token, int depth) {
        String upper = token.text().toUpperCase(Locale.ROOT);
        switch (upper) {
            case "TRUE" -> {
                return count(new Node.Literal(ExpressionType.BOOLEAN, Boolean.TRUE, token.offset()));
            }
            case "FALSE" -> {
                return count(new Node.Literal(ExpressionType.BOOLEAN, Boolean.FALSE, token.offset()));
            }
            case "NULL" -> {
                return count(new Node.Literal(ExpressionType.NONE, null, token.offset()));
            }
            case "AND", "OR" -> throw fail(Kind.SYNTAX, token.offset(), upper + " joins two conditions: write "
                    + "\"a " + upper + " b\", it is not a function.");
            case "NOT" -> throw fail(Kind.SYNTAX, token.offset(), "NOT goes in front of a whole condition, for "
                    + "example NOT (a = b), or after AND and OR. Anywhere else put it in brackets: (NOT a).");
            default -> {
                if (peek().type() == Type.LPAREN) {
                    return call(token, upper, depth);
                }
                return count(new Node.FieldRef(token.text(), token.offset()));
            }
        }
    }

    private Node call(Token nameToken, String function, int depth) {
        next();
        List<Node> arguments = new ArrayList<>();
        if (peek().type() == Type.RPAREN) {
            next();
        } else {
            while (true) {
                arguments.add(or(depth + 1));
                if (peek().type() == Type.COMMA) {
                    next();
                } else {
                    expect(Type.RPAREN, "Expected a comma or a closing bracket here.");
                    break;
                }
            }
        }
        return count(new Node.Call(function, arguments, nameToken.offset()));
    }

    // ---- helpers ----

    private static BinaryOp comparisonOf(Type type) {
        return switch (type) {
            case EQ -> BinaryOp.EQUAL;
            case NE -> BinaryOp.NOT_EQUAL;
            case LT -> BinaryOp.LESS;
            case LE -> BinaryOp.LESS_OR_EQUAL;
            case GT -> BinaryOp.GREATER;
            case GE -> BinaryOp.GREATER_OR_EQUAL;
            default -> null;
        };
    }

    private static boolean isKeyword(Token token, String keyword) {
        return token.type() == Type.NAME && token.text().equalsIgnoreCase(keyword);
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        Token token = tokens.get(index);
        if (token.type() != Type.END) {
            index++;
        }
        return token;
    }

    private void expect(Type type, String problem) {
        if (peek().type() != type) {
            throw fail(Kind.SYNTAX, peek().offset(), problem);
        }
        next();
    }

    private void enter(int depth) {
        if (depth > limits.maxDepth()) {
            throw fail(Kind.TOO_DEEP, peek().offset(), "Brackets, function arguments and prefix operators are nested "
                    + "deeper than " + limits.maxDepth() + " levels.");
        }
    }

    private <T extends Node> T count(T node) {
        nodes++;
        if (nodes > limits.maxNodes()) {
            throw fail(Kind.TOO_MANY_NODES, node.offset(), "The expression has more than " + limits.maxNodes()
                    + " parts (values, fields, operators and calls). Split it up, for example into two formula "
                    + "fields.");
        }
        return node;
    }

    private ExpressionException missingValue(Token found) {
        String problem = found.type() == Type.END
                ? "The expression ends where a value is missing: write a number, text, a field, a function or a "
                        + "bracket."
                : "A value is missing here: write a number, text, a field, a function or a bracket.";
        return fail(Kind.SYNTAX, found.offset(), problem);
    }

    private ExpressionException unexpected(Token found) {
        String problem = switch (found.type()) {
            case NUMBER, TEXT, NAME, LPAREN -> "Two values stand next to each other: an operator (+ - * / % = != < "
                    + "<= > >= AND OR) is missing between them.";
            case RPAREN -> "This closing bracket has no opening bracket.";
            case COMMA -> "A comma is only used between the arguments of a function.";
            default -> "An operator, a closing bracket or the end of the expression is expected here.";
        };
        return fail(Kind.SYNTAX, found.offset(), problem);
    }

    private ExpressionException fail(Kind kind, int offset, String problem) {
        return new ExpressionException(kind, ExpressionException.Position.of(source, offset), problem);
    }
}
