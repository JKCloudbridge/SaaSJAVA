package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The requests of one record, newest first, each in the shape the approval flow already draws (FA6E, ADR-0199). A
 * request the caller may not see in full has its steps and no history, as in the panel of a record.
 *
 * @param recordId the record
 * @param requests its requests, newest first; empty when it never had one
 */
public record RecordApprovalHistoryView(@NotNull UUID recordId, @NotNull List<ApprovalInstanceView> requests) {

    /** Copies the list. */
    public RecordApprovalHistoryView {
        requests = List.copyOf(requests);
    }
}
