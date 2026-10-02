package app.platform.security;

import java.util.UUID;

/**
 * A reference to one record of an object, for the decision API (ADR-0050). Record-level security arrives in Sprint 17;
 * until then a decision is accepted with a record and answered exactly as without one, so the callers (the data engine,
 * Sprint 14) can already pass it and nothing about their calls changes when the rule starts to apply.
 *
 * @param id the record's identifier
 */
public record RecordRef(UUID id) {
}
