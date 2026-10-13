package app.platform.data.internal;

import app.platform.data.QueryPlan.After;
import app.platformapi.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The cursor of a page of a query (Sprint 16, ADR-0100): where the page ended (the sort values of its last record and
 * its identifier) and which query it belongs to. Opaque text for the caller.
 *
 * <p>It is not a secret and not signed, like every cursor of the platform (ADR-0011): a made-up cursor can only move
 * the caller within a result they are allowed to read, because every page is checked again from the start (field
 * permissions, the filter, the security of the records). What it does carry is a short fingerprint of the query it
 * came from, and a cursor of another query is refused rather than followed to a wrong place. It holds only values of
 * fields the caller can read (the sort keys passed the field permission check), never more.
 */
@Component
class QueryCursor {

    static final int MAX_LENGTH = 4096;
    private static final String INVALID = "Is not a valid cursor for this query.";

    private final RecordJson json;

    QueryCursor(RecordJson json) {
        this.json = json;
    }

    /** The cursor that continues after the record whose sort values and identifier are given. */
    String encode(String signature, List<String> values, UUID id) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("q", signature);
        position.put("v", values);
        position.put("i", id.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.write(position).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor of this query.
     *
     * @param cursor the text the caller sent
     * @param signature the fingerprint of the query being asked
     * @param keys the number of keys of its order
     * @throws ApiException {@code VALIDATION_ERROR} at {@code cursor} for anything that is not a cursor of this query
     */
    After decode(String cursor, String signature, int keys) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw ApiException.validation("cursor", INVALID);
        }
        try {
            Map<String, Object> position = json.read(new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8));
            if (!signature.equals(position.get("q")) || !(position.get("v") instanceof List<?> raw)
                    || raw.size() != keys || !(position.get("i") instanceof String id)) {
                throw ApiException.validation("cursor", INVALID);
            }
            List<String> values = new ArrayList<>();
            for (Object value : raw) {
                if (value != null && !(value instanceof String)) {
                    throw ApiException.validation("cursor", INVALID);
                }
                values.add((String) value);
            }
            return new After(values, UUID.fromString(id));
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw ApiException.validation("cursor", INVALID);
        }
    }
}
