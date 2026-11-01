/**
 * Public API module (Sprint 30, ADR-0244 to ADR-0249): a small, stable interface over the dynamic objects for other
 * people's software, beside the internal interface that the screens and the sandboxed pages call. It has its own
 * address prefix ({@code /api/public/v1}), its own contract document, its own versioning and deprecation policy
 * ({@code docs/public-api.md}) and its own types, so the internal interface can keep changing with the screens.
 *
 * <p>It is a new door on the same house, not a second house: every operation calls {@code Records},
 * {@code RecordBulk}, {@code IdempotentWrites} or {@code ObjectDescriptions} of the {@code data} module, so the gate,
 * field security, record security, record locks, the write pipeline and the audit trail apply unchanged and no rule of
 * who may see or change a record is written twice. The module reads no table, writes no statement and names no
 * object of its own; nothing depends on it.
 *
 * <p>The types of this package are the public contract: a property is never added to one of them without its place
 * in the contract document, and a breaking change fails {@code PublicContractBaselineTest}. Everything in
 * sub-packages is internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Public API",
        allowedDependencies = {
            "data",
            "tenant",
            "sharedkernel",
            "observability"
        })
package app.platform.publicapi;

import org.springframework.modulith.ApplicationModule;
