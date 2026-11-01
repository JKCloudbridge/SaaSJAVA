package app.platform.integration.adapter;

import java.util.List;
import java.util.Map;

/**
 * A connector type whose system exchanges files (Sprint 29A, ADR-0237, ADR-0240). It sends one file per change through
 * {@link #send} (the framework chooses the name and writes it atomically) and reads the files that arrive into
 * entities through {@link #read}. Like every adapter it is declarative: it opens no connection, reads no secret and
 * touches no database.
 */
public interface FileFlowConnector extends Connector {

    @Override
    default Transport transport() {
        return Transport.SFTP;
    }

    /** The directories, the format and the timing of the connection, read from settings that passed their checks. */
    FileLayout layout(Map<String, String> settings);

    /**
     * Reads the whole content of a file into entities, one per row, in order. All or nothing: a file with a problem is
     * refused as a whole, so a half-read file is never applied.
     *
     * @param settings the settings of the connection
     * @param content the bytes of the file, bounded by the framework
     * @param maxRows the most rows a file may hold
     * @throws FileFormatException when the file cannot be read as the connection says
     */
    List<CanonicalEntity> read(Map<String, String> settings, byte[] content, int maxRows);
}
