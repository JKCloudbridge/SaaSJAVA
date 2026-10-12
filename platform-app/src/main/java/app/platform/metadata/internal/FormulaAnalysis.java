package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.Expression;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.expression.Purpose;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What is true of the formula fields of one object (ADR-0077): which of them pass the expression language, which fields
 * each one uses, which of them form a circle, how many formulas are stacked on each, and in what order they must be
 * calculated. The dependency checks, the compiled artefacts of the runtime, the report of stored formulas and the
 * object view all read this one analysis, so they cannot disagree.
 *
 * <p>A formula that was stored before the language existed (its configuration is not {@code verified}) and does not
 * pass
 * is only reported: it is neither trusted nor an obstacle, because refusing every publication of an organization for an
 * old formula would punish the wrong change. Saving it again makes it verified, and from then on it is checked like any
 * other.
 */
final class FormulaAnalysis {

    /**
     * One formula field.
     *
     * @param field the field
     * @param verified whether it was saved under the checks of Sprint 13
     * @param uses the fields its text names (what could be read of it, also when it does not pass)
     * @param compiled the compiled formula, or null when it does not pass
     * @param problem why it does not pass, or null when it does
     */
    record Entry(FieldDefinition field, boolean verified, List<String> uses, Compiled compiled,
            ExpressionException problem) {

        /** Copies the list. */
        Entry {
            uses = List.copyOf(uses);
        }

        boolean valid() {
            return compiled != null;
        }

        /** Whether its list of used fields can be believed: it passes, or it was saved under the checks. */
        boolean trusted() {
            return valid() || verified;
        }
    }

    /** A circle of formulas: the fields in order, the first repeated at the end. */
    record Circle(List<String> path) {

        /** Copies the list. */
        Circle {
            path = List.copyOf(path);
        }
    }

    /** A formula with too many formulas under it: its depth and the chain that makes the depth. */
    record TooDeep(String field, int depth, List<String> chain) {

        /** Copies the list. */
        TooDeep {
            chain = List.copyOf(chain);
        }
    }

    private final String objectApiName;
    private final Map<String, Entry> entries;
    private final List<Circle> circles = new ArrayList<>();
    private final List<TooDeep> tooDeep = new ArrayList<>();
    private final Map<String, Integer> depths = new HashMap<>();
    private final Set<String> circular = new HashSet<>();

    private FormulaAnalysis(String objectApiName, Map<String, Entry> entries, int maxDepth) {
        this.objectApiName = objectApiName;
        this.entries = Collections.unmodifiableMap(entries);
        findCircles();
        measureDepths(maxDepth);
    }

    /** Analyses every formula field of the object. */
    static FormulaAnalysis of(ObjectDefinition object, ExpressionEngine engine, int maxDepth) {
        return of(object, object, engine, maxDepth);
    }

    /**
     * Analyses every formula field that survives in {@code object}, compiled against the fields of
     * {@code schemaObject} instead. A dependency check (ADR-0077) passes the object as it was <em>before</em> the
     * proposed change here, so that a formula already trusted does not look broken only because the very change
     * being checked is the one removing a field it uses; {@code object} still decides which formula fields are even
     * asked about, so a formula removed together with the field it needs is correctly left out, exactly as before.
     */
    static FormulaAnalysis of(ObjectDefinition object, ObjectDefinition schemaObject, ExpressionEngine engine,
            int maxDepth) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, ExpressionType> schema = null;
        for (FieldDefinition field : object.fields()) {
            if (field.configuration() instanceof FormulaConfiguration formula) {
                if (schema == null) {
                    // Most objects have no formula, so the map of types is made only for the ones that have.
                    schema = ExpressionSupport.schemaOf(schemaObject);
                }
                entries.put(field.apiName(), entry(engine, schema, field, formula));
            }
        }
        return new FormulaAnalysis(object.apiName(), entries, maxDepth);
    }

    private static Entry entry(ExpressionEngine engine, Map<String, ExpressionType> schema, FieldDefinition field,
            FormulaConfiguration formula) {
        Expression parsed;
        try {
            parsed = engine.parse(formula.expression());
        } catch (ExpressionException problem) {
            return new Entry(field, formula.verified(), List.of(), null, problem);
        }
        try {
            Compiled compiled = engine.compile(formula.expression(), schema, Purpose.FORMULA,
                    ExpressionSupport.typeOf(formula.resultType()));
            return new Entry(field, formula.verified(), compiled.fields(), compiled, null);
        } catch (ExpressionException problem) {
            return new Entry(field, formula.verified(), parsed.fields(), null, problem);
        }
    }

    // ---- what it found ----

    Map<String, Entry> entries() {
        return entries;
    }

    List<Circle> circles() {
        return Collections.unmodifiableList(circles);
    }

    List<TooDeep> tooDeep() {
        return Collections.unmodifiableList(tooDeep);
    }

    /** How many formulas are stacked on the formula, itself included (1 when it uses no formula). */
    int depthOf(String field) {
        return depths.getOrDefault(field, 1);
    }

    /** Whether the formula is part of a circle. */
    boolean inCircle(String field) {
        return circular.contains(field);
    }

    /**
     * The formulas that pass, the ones a formula uses before the formula itself, so that calculating them in this order
     * never needs a value that is not calculated yet. A formula in a circle is left out.
     */
    List<String> calculationOrder() {
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String name : entries.keySet()) {
            visit(name, done, order);
        }
        return order;
    }

    private void visit(String name, Set<String> done, List<String> order) {
        Entry entry = entries.get(name);
        if (entry == null || !entry.valid() || circular.contains(name) || !done.add(name)) {
            return;
        }
        for (String used : entry.uses()) {
            visit(used, done, order);
        }
        order.add(name);
    }

    // ---- circles ----

    private List<String> formulasUsedBy(String name) {
        Entry entry = entries.get(name);
        if (entry == null || !entry.trusted()) {
            return List.of();
        }
        return entry.uses().stream().filter(entries::containsKey).toList();
    }

    private void findCircles() {
        Set<String> finished = new HashSet<>();
        Set<String> seen = new TreeSet<>();
        for (String start : entries.keySet()) {
            walk(start, new ArrayList<>(), finished, seen);
        }
    }

    private void walk(String name, List<String> path, Set<String> finished, Set<String> seen) {
        int at = path.indexOf(name);
        if (at >= 0) {
            List<String> circle = new ArrayList<>(path.subList(at, path.size()));
            circle.add(name);
            if (seen.add(String.join(",", new TreeSet<>(circle)))) {
                circles.add(new Circle(circle));
            }
            circular.addAll(path.subList(at, path.size()));
            return;
        }
        if (!finished.add(name)) {
            return;
        }
        path.add(name);
        for (String used : formulasUsedBy(name)) {
            walk(used, path, finished, seen);
        }
        path.remove(path.size() - 1);
    }

    // ---- depth ----

    private void measureDepths(int maxDepth) {
        for (String name : entries.keySet()) {
            depth(name, new HashSet<>());
        }
        for (String name : entries.keySet()) {
            int depth = depths.getOrDefault(name, 1);
            if (depth > maxDepth && !circular.contains(name)) {
                tooDeep.add(new TooDeep(name, depth, chainOf(name)));
            }
        }
    }

    private int depth(String name, Set<String> inProgress) {
        Integer known = depths.get(name);
        if (known != null) {
            return known;
        }
        if (circular.contains(name) || !inProgress.add(name)) {
            return 0;
        }
        int deepest = 0;
        for (String used : formulasUsedBy(name)) {
            deepest = Math.max(deepest, depth(used, inProgress));
        }
        inProgress.remove(name);
        depths.put(name, deepest + 1);
        return deepest + 1;
    }

    private List<String> chainOf(String name) {
        List<String> chain = new ArrayList<>();
        String current = name;
        while (current != null) {
            chain.add(current);
            String next = null;
            int best = 0;
            for (String used : formulasUsedBy(current)) {
                int depth = depths.getOrDefault(used, 1);
                if (!circular.contains(used) && depth > best) {
                    best = depth;
                    next = used;
                }
            }
            current = next;
        }
        return chain;
    }

    String objectApiName() {
        return objectApiName;
    }
}
