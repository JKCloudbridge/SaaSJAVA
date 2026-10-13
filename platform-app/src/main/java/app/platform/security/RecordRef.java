package app.platform.security;

import java.util.UUID;

/**
 * A reference to one record of an object, for the decision API (ADR-0050). Since Sprint 17 a decision that is given a
 * record is judged by record-level security as well (ADR-0118): the record must be among the records the member may
 * reach for the action. Only the identifier travels; what is needed to judge the record (its owner, its shares, its
 * values) is read from the record store by the data module, so a caller cannot make a record look different from what
 * it is.
 *
 * @param id the record's identifier
 */
public record RecordRef(UUID id) {
}
