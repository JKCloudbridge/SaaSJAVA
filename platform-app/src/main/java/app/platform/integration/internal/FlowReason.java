package app.platform.integration.internal;

/**
 * Why a send or a receive ended as it did, other than a refusal of the network (those are {@link TestReason}) or of the
 * record store (those are the reasons of the background write). The key is stable and is the only thing stored,
 * counted or shown next to the sentence; the sentence is the platform's own (ADR-0220, ADR-0222). Nothing a record
 * holds, a payload says or a library reports ever reaches a reason.
 */
enum FlowReason {

    REQUIRED_MISSING("required_missing", "A field that must have a value has none."),
    VALUE_NOT_MAPPED("value_not_mapped", "A value has no partner in the value map."),
    TRANSFORM_FAILED("transform_failed", "An expression of the mapping could not be worked out for this record."),
    VALUE_INVALID("value_invalid", "A value does not have the type the mapping says it has."),
    PLATFORM_NEWER("platform_newer", "The record on the platform changed after the outside change was made."),
    NO_MAPPING("no_mapping", "The object has no live mapping for this connection."),
    OUT_OF_SCOPE("out_of_scope", "The connection is not allowed to touch this object this way."),
    ECHO("echo", "The change came from this connection, so it is not sent back."),
    UNCHANGED("unchanged", "Nothing that is sent has changed since the last send."),
    RECORD_GONE("record_gone", "The record no longer exists."),
    NOT_LINKED("not_linked", "The record was never sent, so there is nothing to remove."),
    CONNECTION_OFF("connection_off", "The connection is not switched on."),
    PAYLOAD_UNWRITABLE("payload_unwritable", "The record could not be written in the form the service takes."),
    REJECTED("rejected", "The service did not accept the record."),
    NO_IDENTIFIER("no_identifier", "The service accepted the record but did not say what it calls it."),
    BUSY("busy", "Too many sends were under way, so this one was not made."),
    PAYLOAD_UNREADABLE("payload_unreadable", "The change could not be read."),
    ID_MISSING("id_missing", "The change does not say which entity it is about."),
    UNKNOWN_ENTITY("unknown_entity", "The platform has no record for this entity and may not make one."),
    UPDATE_NOT_ALLOWED("update_not_allowed", "The mapping does not let outside changes alter existing records."),
    NOTHING_TO_APPLY("nothing_to_apply", "No field of the change could be applied."),
    WRITE_REFUSED("write_refused", "The platform did not accept the change."),
    HTTP_TIMEOUT("http_timeout", "The service said the request took too long."),
    SERVER_ERROR("server_error", "The service reported an error of its own."),
    BAD_GATEWAY("bad_gateway", "A gateway in front of the service could not reach it."),
    UNAVAILABLE("unavailable", "The service said it is not available."),
    GATEWAY_TIMEOUT("gateway_timeout", "A gateway in front of the service gave up waiting for it."),
    THROTTLED_BY_SERVICE("throttled_by_service", "The service asked the platform to slow down."),
    CIRCUIT_OPEN("circuit_open", "The service failed repeatedly, so sending to it is paused for a while."),
    THROTTLED("throttled", "The limit on how fast this connection sends was reached, so the send waits its turn."),
    BACKOFF("backoff", "The last attempt failed, so the send waits before it is tried again."),
    QUEUE_FULL("queue_full", "Too many sends were waiting for this connection, so this one was set aside."),
    SUPERSEDED("superseded", "A later change of the same record replaced this send."),
    INTERRUPTED("interrupted", "An instance stopped while this was being sent."),
    REMOTE_FAILED("remote_failed", "The service reported that the job failed."),
    REMOTE_EXPIRED("remote_expired", "The service did not finish the job in time."),
    CONNECTION_REMOVED("connection_removed", "The connection was removed."),
    REPLAYED("replayed", "An administrator sent this again."),
    FILE_UNREADABLE("file_unreadable", "The file could not be read."),
    FILE_TOO_LARGE("file_too_large", "The file is larger than the platform accepts."),
    FILE_NOT_ALLOWED("file_not_allowed", "The name of the file is not one the platform processes."),
    FILE_EMPTY("file_empty", "The file has no row."),
    FILE_NOT_TEXT("file_not_text", "The file is not plain text in the encoding the platform reads."),
    FILE_TOO_MANY_ROWS("file_too_many_rows", "The file has more rows than the platform processes in one file."),
    FILE_HEADER_INVALID("file_header_invalid",
            "The first line of the file does not name the columns the platform needs."),
    FILE_ROW_INVALID("file_row_invalid", "A row of the file is not shaped like the others or has no identifier."),
    FILE_ROWS_REFUSED("file_rows_refused",
            "The file was applied, but some of its rows were refused when they were written."),
    FILE_DUPLICATE("file_duplicate", "A file with the same name, size and time was already processed."),
    FILE_VANISHED("file_vanished", "The file disappeared from the server before it could be read."),
    FILE_EXISTS("file_exists", "A file with the name the platform chose is already there with other content."),
    UNEXPECTED("unexpected", "The work could not be completed.");

    private final String key;
    private final String words;

    FlowReason(String key, String words) {
        this.key = key;
        this.words = words;
    }

    String key() {
        return key;
    }

    String words() {
        return words;
    }

    /** The sentence for a stored key of any family; a key this release does not know gets a plain one. */
    static String wordsFor(String key) {
        if (key == null) {
            return null;
        }
        for (FlowReason reason : values()) {
            if (reason.key.equals(key)) {
                return reason.words;
            }
        }
        for (TestReason reason : TestReason.values()) {
            if (reason.key().equals(key)) {
                return reason.words();
            }
        }
        if (key.startsWith("write_")) {
            return WRITE_REFUSED.words;
        }
        return UNEXPECTED.words;
    }
}
