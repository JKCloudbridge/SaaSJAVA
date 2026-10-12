# ADR-0081: Security review of the expression engine

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0079](0079-checking-and-trying-expressions-without-records.md), [ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md)

## Context

The standing rule is "no arbitrary SQL or executable code from tenant metadata". The expression engine is where an organization's text meets code that interprets it, so
this sprint builds the promise and attacks it. This is the review the sprint prompt asks for.

## What an organization can do

It can write a text of at most 4000 characters in the grammar of [ADR-0076](0076-the-expression-engine-and-its-language.md): arithmetic on decimals, comparisons, logic, the functions of the fixed list, and fields of
the same object. It can use that text as a formula (a stored value) or as the condition of a rule. It can ask the engine whether a text is valid and what it does on sample values (that needs the
abilities of ADR-0079). The most a text can cost is bounded.

## What it cannot do, and why

| Attack | Why it cannot work |
|---|---|
| Run code, call a method, load a class, use reflection | The grammar has no construct for it: no `.`, no call of anything but the fixed library, no string that becomes code. The tree has five kinds of part; the checker, evaluator and printer are `switch` statements over them. |
| Reach a file, the network, a process, the database, another thread | The module does not import them (an architecture test, `ExpressionSandboxTest`, fails the build if it ever does: no reflection, files, network, processes, class loading, concurrency, scripting, Spring, platform modules). |
| Read the clock or the machine (time zone, locale, environment) | The moment is an argument; text and dates use the root locale and UTC; only the engine class reads the system timer, for the time bound. |
| Take the platform down with a big input | Length, parts, nesting, steps, text size and time are bounded and tested with hostile inputs (`ExpressionLimitsTest`): 1,000,000 characters, 200,000 nested brackets, 500 chained calls, a text that doubles, a search built to be slow. Nesting is counted, so the stack is never deep; chains of operators are loops. |
| Make evaluation slow with a pattern | There is no regular expression and no power operator; text search is linear time; decimal results are bounded in digits. |
| Wrap or lose a number | 28 integer digits at most; an overflow is an error; no floating point; `1E999999999` and other exponent forms are not numbers; values from outside are checked before anything is done with them. |
| Hide an instruction with look-alike or invisible characters | Outside a text literal only ASCII exists; inside, control, format (zero-width, right-to-left), private-use, unassigned, separator and lone surrogate characters are refused. |
| Make an error that leaks | Errors name the place and the kind; they never repeat a text literal; they have no stack trace; the expression, the sample values and the answers are never logged or audited (`ExpressionFlowsLogsAreCleanIT`). |
| Make the answer differ between runs or machines | The evaluation is deterministic: the same text and values give the same answer (a property test). |
| Reach another organization's data | Evaluation takes a map of values and touches nothing else; the check and try-it endpoints read only the caller's own catalogue; every endpoint is tested for another organization, a forged header, a platform administrator and an unauthenticated caller. |

## The proof

- **A corpus of unsafe constructs kept as a file** (`src/test/resources/expression/unsafe-constructs.txt`, one per line with the reason): Java calls and reflection, method calls and
  property chains, template and expression-language escapes, JNDI and URL strings, statements and loops, regular expressions, comments, numbers that overflow, look-alike and invisible
  characters, and type mistakes. `UnsafeCorpusTest` asserts that every line is refused at parse or check time (never at evaluation) with a place and a kind, that the message does not repeat
  a literal, and that nothing happens outside the engine. The test can fail: widening the whitelist (adding a function, allowing a dot) turns it red; the experiment is written down in the
  manual testing document.
- **Hostile inputs too big to write down** are made in `ExpressionLimitsTest`.
- **Property-based tests** (`ExpressionPropertyTest`): for any text, the engine answers with a tree or with its own error and nothing else; every run stops within its limits;
  the engine accepts exactly the grammar (checked against a second, independently written recogniser); a tree printed and parsed again is the same tree and gives the same answer; and a
  well typed expression over any values never fails except by the documented errors of a run. These found two real defects while they were written (a function typed with the wrong
  result, a printed form that did not parse back), which is what they are for.
- **`ExpressionSandboxTest`** proves the module cannot name the dangerous parts of the platform.

## What is not covered (honest limits)

- **Text of the organization is shown back to the person who typed it** in the answers of the API (names and places). That is the purpose of the answer; the answers are no-store and need the abilities.
- **The wall-clock bound is a backstop**, not a guarantee: Java cannot stop a running thread safely; the step bound and the size bounds are the real ones. A pause of the machine longer than the bound can
  make a legitimate evaluation fail with a clear error.
- **Memory.** Compiled artefacts are bounded by the limits of definitions and the number of organizations the cache keeps ([ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md)); there is no separate bound.
- **Whether a rule that cannot be evaluated blocks a save** is a decision of the write pipeline (Sprint 15); this module reports it and never passes it in silence.

## Consequences

- Any change to the grammar, the library or the module's dependencies is a change of this ADR, of the corpus and of the properties, and fails the build otherwise.
