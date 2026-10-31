package app.platform.integration.adapter;

import java.util.Optional;

/**
 * The two formats a file flow reads and writes (Sprint 29A, ADR-0240). A closed list: there is no template text and no
 * way to describe a layout of one's own, so a file is always either comma separated values with a header line or one
 * flat JSON object per line.
 */
public enum FileFormat {

    /** Comma separated values; the first line names the columns. */
    CSV("csv", ".csv", "text/csv"),

    /** One flat JSON object per line. */
    JSONL("jsonl", ".jsonl", "application/x-ndjson");

    private final String key;
    private final String extension;
    private final String mediaType;

    FileFormat(String key, String extension, String mediaType) {
        this.key = key;
        this.extension = extension;
        this.mediaType = mediaType;
    }

    /** The stored value of the setting. */
    public String key() {
        return key;
    }

    /** The ending of a file name, with its dot. */
    public String extension() {
        return extension;
    }

    /** The media type of the content. */
    public String mediaType() {
        return mediaType;
    }

    /** The format with this stored value. */
    public static Optional<FileFormat> fromKey(String key) {
        for (FileFormat format : values()) {
            if (format.key.equals(key)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
