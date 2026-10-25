package app.platform.data;

import java.util.Map;
import java.util.UUID;

/** Permanent ownership of issued numbers; records continue to be written only through the record pipeline. */
public interface NumberAssignments {
    boolean fixed(String series, UUID recordId, String currentNumber);

    boolean sameInputs(String series, Map<String, Object> before, Map<String, Object> after);

    void checkAvailable(String series, UUID recordId, String number);

    void reserve(String series, String object, UUID recordId, String number, boolean fixed);
}
