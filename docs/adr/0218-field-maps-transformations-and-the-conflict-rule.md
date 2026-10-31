# ADR-0218: Field maps, transformations and the conflict rule (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0081](0081-security-review-of-the-expression-engine.md), [ADR-0140](0140-loop-and-recursion-protection.md)

## Decision

**A field map** names the external field, the platform field, the direction (`OUTBOUND`, `INBOUND`, `BOTH`), the type the other side carries, whether a value is required, a default, and one conversion from a closed list: `NONE`, `TRIM`, `VALUE_MAP` (both ways, so each value appears in one pair only), `NUMBER_SCALE` (a factor above zero, at most 6 decimals). Anything else is an **expression** of the platform's engine under the new purpose `MAPPING_TRANSFORM`: bounded as every expression is, **no clock** (the same input must give the same output so a mapping can be tried and replayed), never run as code. An outgoing expression reads the fields of the object; an incoming one reads the external fields that come in. A field that goes both ways needs an expression for each way or none.

The compiler (`MappingCompiler`) is pure and checks the whole definition against the object as it is now; a problem is a fixed sentence at a place and never repeats what was typed. A field only the platform writes, a system field, a formula, an auto-number, a link and a multi-select cannot be written from outside; a multi-select cannot be mapped.

**The conflict rule** is per mapping, for fields that go both ways when the record exists: `PLATFORM_UNLESS_NEWER` (recommended; the outside change wins only when the external changed-at field says it is newer than the record's last change; with no time the platform wins), `EXTERNAL_WINS`, `PLATFORM_WINS`. Fields that only come in are always written; a new record is not in conflict. The skipped fields are reported with the reason `platform_newer`.

## Why the engine is not reached from workflow or data

The engine stays pure and the sandbox rules (`ExpressionSandboxTest`) are unchanged. Integration is the second module allowed to use it (new edge `integration -> expression`, recorded in `package-info.java` and `docs/modules.md`).
