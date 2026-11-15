package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Freeze;
import app.platform.sheets.internal.SheetDocument.Input;
import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGridValidator.Checked;
import app.platform.sheets.internal.SheetGridValidator.Problem;
import app.platform.sheets.internal.SheetGridValidator.Result;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Checks a document of format version 2 completely, in a fixed order, and says every place that is wrong in fixed words
 * (SH1A, ADR-0251). It is the staged pipeline of {@link SheetGridValidator} again: <strong>the first stage with a
 * problem answers alone</strong>, a problem is a stable code and a place made of schema names, positions, sheet keys
 * and identifiers, and the words never repeat anything that was typed (the sheet is the organization's text,
 * ADR-0012).
 *
 * <ol>
 *   <li>Shape, against the schema {@code SheetDocument}.</li>
 *   <li>The sheets: how many, their keys, their titles.</li>
 *   <li>The body of every sheet, by the stages of format version 1 (limits, text, structure, cells, merges) with the
 *       one change that a fixed text may be longer. There is no second set of rules for the part the formats
 *       share.</li>
 *   <li>What version 2 adds to the limits: widths and heights, the text typed into input cells.</li>
 *   <li>The cells: a look and a style never state the same thing twice, a link is an https address on fixed text.</li>
 *   <li>The sheet: the frozen panes, and the input cells that state their own type, requirement or class.</li>
 * </ol>
 *
 * <p>The caller has already bounded the size and the nesting, parsed the text without a repeated key, and decided that
 * the switch of format version 2 is on. An accepted document is returned in its <em>canonical</em> form
 * ({@link SheetDocumentWriter}).
 */
final class SheetDocumentValidator {

    private static final int MOST_PROBLEMS = 20;
    /** What a spreadsheet program forbids in the name of a tab, so that a title can always be written to a file. */
    private static final Set<Character> TITLE_FORBIDDEN = Set.of(':', '\\', '/', '?', '*', '[', ']');

    private final SheetProperties.Limits limits;
    /** The limits of the shared stages: the text of a fixed cell may be as long as format version 2 allows. */
    private final SheetProperties.Limits bodyLimits;
    private final SheetSchemas schemas;
    private final FormulaChecks formulas;

    SheetDocumentValidator(SheetProperties.Limits limits, SheetSchemas schemas) {
        this(limits, schemas, FormulaLimits.DEFAULTS);
    }

    SheetDocumentValidator(SheetProperties.Limits limits, SheetSchemas schemas, FormulaLimits formulaLimits) {
        this.limits = limits;
        this.schemas = schemas;
        this.formulas = new FormulaChecks(formulaLimits);
        this.bodyLimits = new SheetProperties.Limits(limits.maxBytes(), limits.maxRows(), limits.maxColumns(),
                limits.maxHeaderRows(), Math.max(limits.maxCellChars(), limits.maxFixedTextChars()),
                limits.maxLabelChars(), limits.maxPicklistValues(), limits.maxMerges(), limits.maxDepth(),
                limits.maxVersionsPerOwner(), limits.maxFixedTextChars(), limits.minColumnWidth(),
                limits.maxColumnWidth(), limits.minRowHeight(), limits.maxRowHeight(), limits.maxSheets(),
                limits.maxFilesPerCell(), limits.maxLinkChars());
    }

    /** Checks a parsed document of format version 2. */
    Result check(JsonNode document) {
        List<Problem> shape = new ArrayList<>();
        for (SheetSchemas.Issue issue : schemas.validateTwo(document, "grid")) {
            shape.add(new Problem("shape", issue.place(), issue.message()));
        }
        if (!shape.isEmpty()) {
            return new Result(null, shape);
        }
        SheetDocument model = SheetDocumentReader.read(document);
        List<Supplier<List<Problem>>> stages = List.of(() -> sheetProblems(model), () -> bodyProblems(model),
                () -> limitProblems(model), () -> cellProblems(model), () -> formulaProblems(model),
                () -> sheetExtraProblems(model));
        for (Supplier<List<Problem>> next : stages) {
            List<Problem> stage = next.get();
            if (!stage.isEmpty()) {
                return new Result(null, stage.size() > MOST_PROBLEMS ? stage.subList(0, MOST_PROBLEMS) : stage);
            }
        }
        SheetDocument normal = SheetDocumentWriter.normalize(model);
        String canonical = SheetDocumentWriter.write(normal);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxBytes()) {
            return new Result(null, List.of(new Problem("too_large", "grid", "The grid is larger than the allowed "
                    + "size of " + limits.maxBytes() + " bytes.")));
        }
        return new Result(new Checked(normal.sheets().get(0).body(), canonical, SheetGridWriter.sha256(bytes),
                bytes.length, normal), List.of());
    }

    // ---- the sheets ----

    private List<Problem> sheetProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        if (document.sheets().size() > limits.maxSheets()) {
            out.add(new Problem("too_many_sheets", "grid.sheets", "There are more sheets than the allowed "
                    + limits.maxSheets() + "."));
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            Tab tab = document.sheets().get(i);
            String place = place(i);
            if (!keys.add(tab.key())) {
                out.add(new Problem("duplicate_sheet_key", place + ".key", "A sheet key is used twice."));
            }
            String title = tab.title();
            if (title != null) {
                if (CellValues.unsafe(title)) {
                    out.add(new Problem("unsafe_text", place + ".title", CellValues.TEXT_UNSAFE));
                } else if (title.isBlank() || title.chars().anyMatch(c -> TITLE_FORBIDDEN.contains((char) c))
                        || title.startsWith("'") || title.endsWith("'")) {
                    out.add(new Problem("title_invalid", place + ".title", "A sheet title cannot be empty, cannot "
                            + "contain : \\ / ? * [ ] and cannot start or end with an apostrophe."));
                }
            }
        }
        return out;
    }

    private List<Problem> bodyProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            for (Problem problem : SheetGridValidator.bodyProblems(document.sheets().get(i).body(), bodyLimits)) {
                // The body's places start with "grid": here they are inside one sheet.
                out.add(new Problem(problem.code(), place(i) + problem.place().substring("grid".length()),
                        problem.message()));
            }
        }
        return out;
    }

    // ---- limits that version 2 adds ----

    private List<Problem> limitProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            Tab tab = document.sheets().get(i);
            SheetGrid body = tab.body();
            for (int c = 0; c < body.columns().size(); c++) {
                Column column = body.columns().get(c);
                String place = place(i) + ".columns[" + c + "]";
                if (column.width() != null
                        && (column.width() < limits.minColumnWidth() || column.width() > limits.maxColumnWidth())) {
                    out.add(new Problem("width_range", place + ".width", "A column is narrower or wider than the "
                            + "allowed " + limits.minColumnWidth() + " to " + limits.maxColumnWidth() + " pixels."));
                }
                if (column.defaultValue() instanceof String text && text.length() > limits.maxCellChars()) {
                    out.add(new Problem("text_too_long", place + ".default", inputWords()));
                }
            }
            heights("headerRows", body.headerRows(), place(i), out);
            heights("rows", body.rows(), place(i), out);
            Set<String> headerIds = headerIds(body);
            for (Map.Entry<String, Cell> entry : new TreeMap<>(body.cells()).entrySet()) {
                boolean header = headerIds.contains(rowOf(entry.getKey()));
                if (!SheetGridValidator.isFixed(entry.getValue(), header)
                        && entry.getValue().value() instanceof String text && text.length() > limits.maxCellChars()) {
                    out.add(new Problem("text_too_long", place(i) + ".cells." + entry.getKey() + ".value",
                            inputWords()));
                }
            }
        }
        return out;
    }

    private void heights(String name, List<Line> lines, String sheet, List<Problem> out) {
        for (int j = 0; j < lines.size(); j++) {
            Integer height = lines.get(j).height();
            if (height != null && (height < limits.minRowHeight() || height > limits.maxRowHeight())) {
                out.add(new Problem("height_range", sheet + "." + name + "[" + j + "].height", "A row is shorter or "
                        + "taller than the allowed " + limits.minRowHeight() + " to " + limits.maxRowHeight()
                        + " pixels."));
            }
        }
    }

    private String inputWords() {
        return "A text typed into a cell is longer than the allowed " + limits.maxCellChars() + " characters.";
    }

    // ---- cells: look, style and link ----

    private List<Problem> cellProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            Tab tab = document.sheets().get(i);
            Set<String> headerIds = headerIds(tab.body());
            for (Map.Entry<String, Cell> entry : new TreeMap<>(tab.body().cells()).entrySet()) {
                String key = entry.getKey();
                Cell cell = entry.getValue();
                String place = place(i) + ".cells." + key;
                SheetLook look = tab.looks().get(key);
                if (look != null && cell.style() != null && statesTwice(cell.style(), look)) {
                    out.add(new Problem("look_conflict", place + ".look", "A cell states its text colour or its "
                            + "size both in its style and in its look. Keep one."));
                }
                String link = tab.links().get(key);
                if (link != null) {
                    if (!validLink(link)) {
                        out.add(new Problem("link_invalid", place + ".link", "A link must be an https address of at "
                                + "most " + limits.maxLinkChars() + " characters, with a host and no user name."));
                    } else if (!(cell.value() instanceof String text) || text.isEmpty()
                            || !SheetGridValidator.isFixed(cell, headerIds.contains(rowOf(key)))) {
                        out.add(new Problem("link_target", place + ".link",
                                "Only a cell with fixed text can carry a link."));
                    }
                }
            }
        }
        return out;
    }

    /** Whether a cell names its text colour or its size both as a word of the style and as a value of the look. */
    private static boolean statesTwice(SheetGrid.Style style, SheetLook look) {
        boolean colour = look.colour() != null && style.colour() != null && style.colour() != SheetGrid.Colour.DEFAULT;
        boolean size = look.size() != null && style.fontSize() != null && style.fontSize() != FontSize.NORMAL;
        return colour || size;
    }

    /** An https address with a host, no user information, no control character, no space, within the limit. */
    private boolean validLink(String link) {
        if (link.length() > limits.maxLinkChars() || !link.startsWith("https://")
                || link.chars().anyMatch(c -> c <= ' ' || c == '\\' || c >= 0x7f)) {
            return false;
        }
        try {
            URI uri = new URI(link);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            // The exception's text quotes the address: say nothing of it.
            return false;
        }
    }

    // ---- formulas ----

    private List<Problem> formulaProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            out.addAll(formulas.check(document.sheets().get(i), place(i)));
        }
        return out;
    }

    // ---- the sheet: panes and stated input cells ----

    private List<Problem> sheetExtraProblems(SheetDocument document) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < document.sheets().size(); i++) {
            Tab tab = document.sheets().get(i);
            freezeProblems(tab, place(i), out);
            inputProblems(tab, place(i), out);
            conditionProblems(tab, place(i), out);
            scoreProblems(tab, place(i), out);
        }
        return out;
    }

    /**
     * The score marker (MC2B, ADR-0261): it names a cell that holds a formula, so the platform can read a percent that
     * the sheet itself calculated. A cell that holds no formula, one that does not exist and a calculated column are
     * refused, with words that name no value.
     */
    private static void scoreProblems(Tab tab, String place, List<Problem> out) {
        if (tab.score() == null) {
            return;
        }
        if (!tab.formulas().containsKey(tab.score())) {
            out.add(new Problem("score_cell", place + ".score",
                    "The cell marked as the score must hold a formula."));
        }
    }

    private static void freezeProblems(Tab tab, String place, List<Problem> out) {
        Freeze freeze = tab.freeze();
        if (freeze == null) {
            return;
        }
        SheetGrid body = tab.body();
        int rowCap = body.headerRows().size() + (body.dimension() == 2 ? body.rows().size() : 0);
        if (freeze.rows() > rowCap || freeze.columns() > body.columns().size()) {
            out.add(new Problem("freeze_range", place + ".freeze", "The frozen panes reach past the sheet."));
        }
    }

    /**
     * The conditional colours (SH1B, ADR-0255): each range lies inside the sheet and is not reversed, a comparison
     * has the number of operands it needs, and a rule paints something.
     */
    private static void conditionProblems(Tab tab, String place, List<Problem> out) {
        if (tab.conditions().isEmpty()) {
            return;
        }
        SheetGrid body = tab.body();
        if (body.dimension() != 2) {
            out.add(new Problem("conditions_need_named_rows", place + ".conditions",
                    "Conditional colours belong to a sheet that names its rows."));
            return;
        }
        Map<String, Integer> rows = new java.util.HashMap<>();
        for (int r = 0; r < body.rows().size(); r++) {
            rows.put(body.rows().get(r).id(), r);
        }
        Map<String, Integer> columns = new java.util.HashMap<>();
        for (int c = 0; c < body.columns().size(); c++) {
            columns.put(body.columns().get(c).id(), c);
        }
        for (int j = 0; j < tab.conditions().size(); j++) {
            SheetDocument.Condition condition = tab.conditions().get(j);
            String at = place + ".conditions[" + j + "]";
            int[] from = position(condition.from(), rows, columns);
            int[] to = position(condition.to(), rows, columns);
            if (from == null || to == null) {
                out.add(new Problem("condition_unknown_cell", at, "A conditional colour points at a row or column "
                        + "that does not exist."));
            } else if (to[0] < from[0] || to[1] < from[1]) {
                out.add(new Problem("condition_range", at, "A conditional colour covers a range whose end lies "
                        + "before its start."));
            }
            if (condition.isBetween() != (condition.value2() != null)) {
                out.add(new Problem("condition_operand", at, "A comparison between two numbers needs both numbers, "
                        + "and no other comparison takes a second one."));
            }
            if (condition.fill() == null && condition.colour() == null) {
                out.add(new Problem("condition_paint", at, "A conditional colour paints a background, a text "
                        + "colour, or both."));
            }
        }
    }

    private static int[] position(String key, Map<String, Integer> rows, Map<String, Integer> columns) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            return null;
        }
        Integer row = rows.get(key.substring(0, colon));
        Integer column = columns.get(key.substring(colon + 1));
        return row == null || column == null ? null : new int[] {row, column};
    }

    private void inputProblems(Tab tab, String place, List<Problem> out) {
        if (tab.inputs().isEmpty()) {
            return;
        }
        SheetGrid body = tab.body();
        if (body.dimension() != 2) {
            out.add(new Problem("inputs_need_named_rows", place + ".inputs",
                    "Stated input cells belong to a sheet that names its rows."));
            return;
        }
        if (!tab.fillable()) {
            out.add(new Problem("inputs_on_info_sheet", place + ".inputs", "An information sheet has no input cells."));
            return;
        }
        FillStructure structure = new FillStructure(body);
        for (Map.Entry<String, Input> entry : new TreeMap<>(tab.inputs()).entrySet()) {
            String key = entry.getKey();
            String at = place + ".inputs." + key;
            int colon = key.indexOf(':');
            String rowId = key.substring(0, colon);
            String columnId = key.substring(colon + 1);
            if (!structure.isNamedRow(rowId) || structure.column(columnId) == null) {
                out.add(new Problem("input_unknown_cell", at,
                        "A stated input cell points at a row or column that does not exist."));
            } else if (!structure.isInput(rowId, columnId)) {
                out.add(new Problem("input_not_an_input_cell", at,
                        "A stated input cell is not a cell that can be typed into."));
            } else {
                oneInput(entry.getValue(), body.cells().get(key), at, out);
            }
        }
    }

    private void oneInput(Input input, Cell cell, String at, List<Problem> out) {
        boolean picklist = "picklist".equals(input.type());
        if (picklist != (input.picklist() != null) || picklist && !validPicklist(input.picklist())) {
            out.add(new Problem("input_picklist", at, "A list cell needs its values (at most "
                    + limits.maxPicklistValues() + "), each different, and no other cell takes values."));
        }
        Integer files = input.maxFiles();
        if (files != null && (!"attachment".equals(input.type()) || files < 1 || files > limits.maxFilesPerCell())) {
            out.add(new Problem("input_max_files", at, "An attachment cell holds 1 to " + limits.maxFilesPerCell()
                    + " files, and no other cell takes a limit."));
        }
        if (input.type() != null && cell != null && cell.value() != null) {
            out.add(new Problem("input_preset", at, "A cell with a stated type holds no preset value."));
        }
    }

    private boolean validPicklist(List<String> values) {
        if (values.isEmpty() || values.size() > limits.maxPicklistValues()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value.isBlank() || CellValues.unsafe(value) || !seen.add(value)) {
                return false;
            }
        }
        return true;
    }

    // ---- helpers ----

    private static String place(int sheet) {
        return "grid.sheets[" + sheet + "]";
    }

    private static String rowOf(String cellKey) {
        return cellKey.substring(0, cellKey.indexOf(':'));
    }

    private static Set<String> headerIds(SheetGrid body) {
        Set<String> ids = new HashSet<>();
        body.headerRows().forEach(line -> ids.add(line.id()));
        return ids;
    }
}
