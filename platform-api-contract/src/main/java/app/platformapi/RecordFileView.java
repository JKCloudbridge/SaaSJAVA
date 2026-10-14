package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A file attached to a record (Sprint 17). The bytes are fetched from the content endpoint; nothing here is an address
 * of the object store.
 *
 * @param id the file
 * @param name the name, cleaned by the server (the last part of the name given, safe characters)
 * @param contentType the type the server decided
 * @param size the number of bytes stored
 * @param sha256 the SHA-256 of the bytes, in lower case hexadecimal, made by the server while storing
 * @param createdAt when it was stored
 * @param createdBy the user who stored it
 */
public record RecordFileView(@NotNull UUID id, @NotNull String name, @NotNull String contentType, long size,
        @NotNull String sha256, @NotNull Instant createdAt, @NotNull UUID createdBy) {

    @Override
    public String toString() {
        return "RecordFileView[redacted]";
    }
}
