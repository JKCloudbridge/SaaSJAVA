package app.platform.integration.adapter;

import java.time.Duration;
import java.util.Objects;

/**
 * Where a file flow keeps its files and how it reads them, as a connection's settings give them (Sprint 29A,
 * ADR-0240). The framework decides what to do with a layout; an adapter only builds it from the settings.
 *
 * @param inbox where files from the other side wait to be picked up
 * @param processing where a picked-up file is moved while it is applied, so nobody else takes it
 * @param done where a file that was applied completely is moved
 * @param error where a file that was set aside is moved, for a person to look at
 * @param outbox where files for the other side are written
 * @param format the format of every file, in both directions
 * @param inboundEntity the kind of entity the files of the inbox hold, or null when nothing is picked up
 * @param idColumn the column or key that holds the identifier of the entity
 * @param operationColumn the column or key in which a written file says what happened (create, update or delete)
 * @param pollEvery how often the inbox is looked at
 * @param operationTimeout how long a whole visit to the server may take
 */
public record FileLayout(String inbox, String processing, String done, String error, String outbox, FileFormat format,
        String inboundEntity, String idColumn, String operationColumn, Duration pollEvery,
        Duration operationTimeout) {

    public FileLayout {
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(processing, "processing");
        Objects.requireNonNull(done, "done");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(idColumn, "idColumn");
        Objects.requireNonNull(operationColumn, "operationColumn");
        Objects.requireNonNull(pollEvery, "pollEvery");
        Objects.requireNonNull(operationTimeout, "operationTimeout");
    }

    /** Whether files are picked up from the inbox at all. */
    public boolean pulls() {
        return inboundEntity != null;
    }
}
