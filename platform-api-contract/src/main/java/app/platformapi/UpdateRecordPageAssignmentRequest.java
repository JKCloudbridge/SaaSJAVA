package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Points an existing assignment at another record page (Sprint 20).
 *
 * @param recordPage the API name of a record page of the same object
 * @param version the version the caller last read
 */
public record UpdateRecordPageAssignmentRequest(@NotBlank @Size(max = 60) String recordPage, @NotNull Long version) {
}
