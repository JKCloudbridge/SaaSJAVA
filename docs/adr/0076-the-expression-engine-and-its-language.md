# ADR-0076: The expression engine and its language

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0060](0060-field-types-and-their-configuration.md), [ADR-0070](0070-definitions-are-json-checked-by-a-schema.md), [ADR-0077](0077-formula-fields-are-checked-and-are-edges-of-the-graph.md), [ADR-0081](0081-security-review-of-the-expression-engine.md)

## Context

An organization writes a formula (a field whose value is worked out from other fields) and a validation rule (a condition that
rejects a save) in text. Architecture notes 3 (sections 16 to 19) and 6 (sections 2 to 9) and the requirements (R7, R13) say the same
thing: one controlled expression model, parsed into a tree and interpreted by a whitelisted evaluator, never Java, never reflection,
sandboxed, with dependencies and cycle detection. Sprint 13 builds it. The same engine will serve the write pipeline (Sprint 15), workflow
conditions (22) and integration mapping (28), so it must not depend on the database, on HTTP or on a tenant.

## Decision

1. **A logical module `expression`** (package `app.platform.expression`, allowed dependencies: `sharedkernel` only, in practice none). Pure
   logic: no database, no Spring, no I/O, no clock of its own. `metadata` depends on it now (new edge `metadata -> expression`); `data`,
   `workflow` and `integration` will later. Public API in the root package: `ExpressionEngine` (parse, check, compile, evaluate, print),
   `Expression`, `Compiled`, `ExpressionType`, `Purpose`, `Limits`, `ExpressionException`, `FunctionInfo`. Everything else is internal.
2. **A hand-written parser and no library.** A general-purpose expression language of a library can call methods and reach classes; it would
   need a sandbox on top of it, and the promise "nothing an organization types can reach anything" would rest on that sandbox being right. A
   small recursive descent parser with a sealed tree and `switch` over it has nothing to escape from: the grammar has no construct for a call
   of a method, a property, a class or a string of code. No new dependency was added.
3. **The grammar.** Literals: numbers (plain digits with an optional point, no exponent), text in single quotes (a doubled quote is the
   quote), `TRUE`, `FALSE`, `NULL`. Field references by API name (same object only). Operators `+ - * / %`, `= != < <= > >=`, `AND OR NOT`,
   brackets, and calls of a fixed list of functions. From the loosest to the tightest: `OR`, `AND`, `NOT`, a comparison (not chained), `+ -`,
   `* / %`, a prefix `-`. `NOT` stands in front of a whole condition or after `AND`, `OR`, an opening bracket or a comma; anywhere else it is
   written in brackets. There are no comments, no assignment, no loops, no recursion, no user-defined functions, no regular expressions, no
   power operator, no method call, no property chain, no `.`. Keywords and function names are not case sensitive; field names are.
4. **Characters.** Outside a text literal only ASCII letters, digits, the operators and spaces exist, so look-alike letters, zero-width
   characters and direction marks cannot hide in a name. Inside a literal any printable character is allowed but never a control, format,
   private-use, unassigned, separator or lone surrogate character. A name is at most 80 characters.
5. **Types.** `BOOLEAN`, `NUMBER` (every numeric field type), `TEXT` (text, e-mail, phone, URL, picklists, the identifier of a lookup,
   auto-number), `DATE`, `DATETIME` (UTC), `TIME`; the word `NULL` has a type of its own that fits anywhere. A type checker walks the tree
   once against a map of field name to type supplied by the caller (nothing from a database) and says what type the expression gives, which
   fields it uses and whether it reads the clock, or refuses with the place. Mixed types are refused at check time (a text is never
   silently turned into a number), except the date arithmetic below.
6. **Numbers are decimals.** At most 28 digits before the point and 10 after; a result with more fraction digits is rounded half away from
   zero, a result with more integer digits is an `OVERFLOW` error and never a wrapped value. `1E999999999` is not a number (no exponent
   form). Division and remainder by zero are errors that name the place. Text is turned into a number by hand, never through an exponent.
   `0.1 + 0.2` is `0.3`.
7. **The empty value is written down.** A field with no value, `NULL` and the empty text are all "no value" (the empty text does not exist as
   a value). An arithmetic or comparison operation with no value in it gives no value; `x = NULL` and `x != NULL` (and the same with the
   empty text) ask whether there is a value and always answer true or false; `AND` and `OR` use three-valued logic (`FALSE AND blank` is
   false, `TRUE OR blank` is true, otherwise blank); `NOT` of no value is no value; `IF` takes the else branch when its condition has no
   value; text functions that give text give no value for no value (`LEN` gives 0, the predicates `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`
   give false, `CONCAT` counts it as nothing); `ISBLANK` and `COALESCE` are how a person handles it.
8. **Text and dates are deterministic.** Text is compared letter for letter (code point order, capital and small letters differ); `UPPER`
   and `LOWER` use the root locale; dates and times are ISO and UTC; the years are 1 to 9999. `CONTAINS` uses a linear-time search, so a text
   built to be slow cannot make it slow. The moment of `TODAY()` and `NOW()` is an argument of the evaluation, never read inside.
9. **The fixed list of functions** (30): `IF`, `ISBLANK`, `COALESCE`, `ABS`, `ROUND`, `FLOOR`, `CEILING`, `MIN`, `MAX`, `LEN`, `UPPER`,
   `LOWER`, `TRIM`, `LEFT`, `RIGHT`, `SUBSTRING`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `CONCAT`, `TEXT`, `VALUE`, `DATE`, `YEAR`, `MONTH`, `DAY`,
   `ADD_DAYS`, `DAYS_BETWEEN`, `TODAY`, `NOW`; date arithmetic with `+` and `-` on a date and a number of days, and `-` between two dates. No
   function can be added by an organization. A function is one entry in `Functions` with its typing rule and its code; the recipe is in
   `docs/metadata-guide.md`. `IF`, `COALESCE`, `AND` and `OR` run only the part they need, so `IF(b = 0, 0, a / b)` never divides by zero.
10. **Limits, enforced and tested.** 4000 characters (the length of a formula), 500 parts (values, fields, operators, calls), 32 levels of
    nesting (brackets, arguments, prefix operators: refused by a counter, so the recursion is never deeper than a few hundred frames),
    10,000 evaluation steps (every part is a step and work on a long text costs more), 100,000 characters of a text value or result, and 50
    milliseconds of evaluation as the last line of defence (the step bound is the real one; Java cannot stop a running thread safely). The
    settings are `platform.expression.limits.*`; the length is the field's own bound. A bound that is reached is an error that names the
    bound.
11. **Errors name the place and the kind, never the typed text.** `ExpressionException` carries a kind, a line and a column and plain words.
    The words may repeat the name of a field or a function (names have a fixed, safe shape), never a text literal. The exception has no
    stack trace: a flood of them (a fuzz run, an attack) stays cheap. The answer of the API may show the message to the person who typed the
    expression; it is never logged and never written to the audit trail.
12. **One door.** Everything goes through `ExpressionEngine`; the lexer, parser, checker, evaluator and library are package-private behind a
    single public bridge. The engine holds only its limits and is shared by every thread.

## Differences from the written notes (flagged)

- Note 6 writes `==`, `!=` and `null`, lists `SUM()`, `AND()`, `OR()`, `DATE()`, `YEAR()`, `MONTH()` and `TODAY()` among the formula
  functions, and reads `salary > 0` as the condition that must hold. We write `=` (a single sign; `==` is refused with the hint), keep `AND`
  and `OR` as operators, drop `SUM` (an aggregate over records, which arrive with the data engine), and the validation rule is the opposite
  reading (ADR-0078). `TODAY()` and `NOW()` are for rules only (ADR-0077).
- The requirement text "IF ... THEN reject save" is the reading of a rule: the expression is the condition, true rejects.

## Consequences

- Adding a type, an operator or a construct is a change of the grammar and of this ADR, with a new corpus line and a new property.
- The engine costs no database access to evaluate: it takes a map of values.
- The module is proved small enough to attack: see [ADR-0081](0081-security-review-of-the-expression-engine.md).
