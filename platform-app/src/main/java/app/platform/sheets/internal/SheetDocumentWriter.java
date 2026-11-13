package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Freeze;
import app.platform.sheets.internal.SheetDocument.Input;
import app.platform.sheets.internal.SheetDocument.InputClass;
import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaPrinter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gives a checked document of format version 2 its one form, writes it as its <em>canonical</em> JSON text, and so
 * defines its hash (SH1A, ADR-0251). It follows the rules of {@link SheetGridWriter}: compact, every object's keys in
 * the natural order of their characters, nothing that says only what its absence says. The order of the sheets is the
 * order of the tabs and <strong>is</strong> part of the text; the order of keys is not.
 *
 * <p>What is dropped, because its absence means the same: a sheet that is fillable, frozen panes of nothing, a stated
 * input cell that states nothing, the class {@code filler}, a requirement that is false, five files for an attachment
 * cell, and a look or a part of a look that says what the page already does. A colour is written in lower case.
 *
 * <p>It runs only on a document the validator accepted. The body of each sheet is normalised and written by the
 * version 1 code, so a sheet has one canonical body whichever format holds it.
 */
final class SheetDocumentWriter {

    /**
     * The bounds the canonical printing parses with. The formulas of a document that reaches the writer were checked
     * against the real settings; printing must never refuse one that was accepted, so it parses with the ceilings.
     */
    private static final FormulaLimits CANON = new FormulaLimits(4000, 32, 5000, 1, 1, 1, 1, 1);

    private SheetDocumentWriter() {
    }

    /** The document with every sheet in its one form. */
    static SheetDocument normalize(SheetDocument document) {
        List<Tab> sheets = new ArrayList<>();
        for (Tab tab : document.sheets()) {
            Map<String, SheetLook> looks = new TreeMap<>();
            tab.looks().forEach((key, look) -> {
                SheetLook one = look.normalized();
                if (one != null) {
                    looks.put(key, one);
                }
            });
            Map<String, Input> inputs = new TreeMap<>();
            tab.inputs().forEach((key, input) -> {
                Input one = normalize(input);
                if (one != null) {
                    inputs.put(key, one);
                }
            });
            Freeze freeze = tab.freeze() == null || tab.freeze().isNone() ? null : tab.freeze();
            List<SheetDocument.Condition> conditions = new ArrayList<>();
            for (SheetDocument.Condition one : tab.conditions()) {
                conditions.add(new SheetDocument.Condition(one.from(), one.to(), one.operator(), one.value(),
                        one.value2(), lower(one.fill()), lower(one.colour())));
            }
            Map<String, String> formulas = new TreeMap<>();
            tab.formulas().forEach((key, formula) -> formulas.put(key, FormulaPrinter.canonical(formula, CANON)));
            Map<String, String> columnFormulas = new TreeMap<>();
            tab.columnFormulas().forEach((id, formula) -> columnFormulas.put(id,
                    FormulaPrinter.canonical(formula, CANON)));
            sheets.add(new Tab(tab.key(), tab.title(), tab.fillable(), freeze, tab.print(), inputs,
                    SheetGridNormalizer.normalize(tab.body()), looks, new TreeMap<>(tab.links()), conditions, formulas,
                    columnFormulas));
        }
        return new SheetDocument(sheets);
    }

    private static String lower(String colour) {
        return colour == null ? null : colour.toLowerCase(java.util.Locale.ROOT);
    }

    private static Input normalize(Input input) {
        boolean attachment = "attachment".equals(input.type());
        Integer maxFiles = attachment && input.maxFiles() != null && input.maxFiles() != SheetDocument.DEFAULT_MAX_FILES
                ? input.maxFiles() : null;
        Input one = new Input(input.type(), input.required(), input.cls(), input.picklist(), maxFiles);
        boolean says = one.type() != null || one.required() || one.cls() != InputClass.FILLER || one.picklist() != null
                || one.maxFiles() != null;
        return says ? one : null;
    }

    /** The canonical text of a document that has been normalised. */
    static String write(SheetDocument document) {
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", SheetDocument.FORMAT_VERSION);
        List<Object> sheets = new ArrayList<>();
        for (Tab tab : document.sheets()) {
            sheets.add(sheet(tab));
        }
        root.put("sheets", sheets);
        return SheetGridWriter.json(root);
    }

    private static Map<String, Object> sheet(Tab tab) {
        Map<String, Object> out = SheetGridWriter.body(tab.body());
        out.put("key", tab.key());
        if (tab.title() != null) {
            out.put("title", tab.title());
        }
        if (!tab.fillable()) {
            out.put("fillable", false);
        }
        if (tab.freeze() != null && !tab.freeze().isNone()) {
            Map<String, Object> freeze = new TreeMap<>();
            freeze.put("rows", tab.freeze().rows());
            freeze.put("columns", tab.freeze().columns());
            out.put("freeze", freeze);
        }
        if (tab.print() != null) {
            Map<String, Object> print = new TreeMap<>();
            print.put("paper", tab.print().paper());
            print.put("orientation", tab.print().orientation());
            print.put("fitToWidth", tab.print().fitToWidth());
            print.put("repeatHeaderRows", tab.print().repeatHeaderRows());
            out.put("print", print);
        }
        mergeCells(out, tab);
        if (!tab.conditions().isEmpty()) {
            List<Object> conditions = new ArrayList<>();
            for (SheetDocument.Condition one : tab.conditions()) {
                Map<String, Object> condition = new TreeMap<>();
                condition.put("from", one.from());
                condition.put("to", one.to());
                condition.put("operator", one.operator());
                condition.put("value", one.value());
                if (one.value2() != null) {
                    condition.put("value2", one.value2());
                }
                if (one.fill() != null) {
                    condition.put("fill", one.fill());
                }
                if (one.colour() != null) {
                    condition.put("colour", one.colour());
                }
                conditions.add(condition);
            }
            out.put("conditions", conditions);
        }
        if (!tab.inputs().isEmpty()) {
            Map<String, Object> inputs = new TreeMap<>();
            tab.inputs().forEach((key, input) -> inputs.put(key, input(input)));
            out.put("inputs", inputs);
        }
        return out;
    }

    /** The look and the link of a cell go into the cell the body already wrote, or make one. */
    @SuppressWarnings("unchecked") // the body is built by SheetGridWriter, which puts a map of cells under "cells"
    private static void mergeCells(Map<String, Object> sheet, Tab tab) {
        Map<String, Object> cells = (Map<String, Object>) sheet.get("cells");
        tab.looks().forEach((key, look) -> cell(cells, key).put("look", look(look)));
        tab.links().forEach((key, link) -> cell(cells, key).put("link", link));
        tab.formulas().forEach((key, formula) -> cell(cells, key).put("formula", formula));
        if (!tab.columnFormulas().isEmpty()) {
            @SuppressWarnings("unchecked") // the columns are maps built by SheetGridWriter
            List<Map<String, Object>> columns = (List<Map<String, Object>>) sheet.get("columns");
            for (Map<String, Object> column : columns) {
                String formula = tab.columnFormulas().get((String) column.get("id"));
                if (formula != null) {
                    column.put("formula", formula);
                }
            }
        }
    }

    @SuppressWarnings("unchecked") // the cells map holds maps, see above
    private static Map<String, Object> cell(Map<String, Object> cells, String key) {
        return (Map<String, Object>) cells.computeIfAbsent(key, k -> new TreeMap<String, Object>());
    }

    private static Map<String, Object> look(SheetLook look) {
        Map<String, Object> out = new TreeMap<>();
        if (look.font() != null) {
            out.put("font", look.font());
        }
        if (look.size() != null) {
            out.put("size", look.size());
        }
        if (look.colour() != null) {
            out.put("colour", look.colour());
        }
        if (look.fill() != null) {
            out.put("fill", look.fill());
        }
        if (look.wrap()) {
            out.put("wrap", true);
        }
        if (look.verticalAlignment() != null) {
            out.put("verticalAlignment", look.verticalAlignment());
        }
        if (look.borders() != null && !look.borders().isEmpty()) {
            Map<String, Object> sides = new LinkedHashMap<>();
            put(sides, "top", look.borders().top());
            put(sides, "right", look.borders().right());
            put(sides, "bottom", look.borders().bottom());
            put(sides, "left", look.borders().left());
            out.put("borders", new TreeMap<>(sides));
        }
        if (look.numberFormat() != null) {
            out.put("numberFormat", look.numberFormat());
        }
        return out;
    }

    private static void put(Map<String, Object> sides, String name, String style) {
        if (style != null) {
            sides.put(name, style);
        }
    }

    private static Map<String, Object> input(Input input) {
        Map<String, Object> out = new TreeMap<>();
        if (input.type() != null) {
            out.put("type", input.type());
        }
        if (input.required()) {
            out.put("required", true);
        }
        if (input.cls() == InputClass.REVIEWER) {
            out.put("class", input.cls().json());
        }
        if (input.picklist() != null) {
            out.put("picklist", Map.of("values", input.picklist()));
        }
        if (input.maxFiles() != null) {
            out.put("maxFiles", input.maxFiles());
        }
        return out;
    }
}
