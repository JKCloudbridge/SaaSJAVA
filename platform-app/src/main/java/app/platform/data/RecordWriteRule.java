package app.platform.data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted domain contributions inside the ordinary record pipeline, after member input has passed its checks. */
public interface RecordWriteRule {

    /** The API name of the object this rule serves; of the first, when it serves several ({@link #serves}). */
    String object();

    /**
     * Whether the rule serves the object (FA13B, ADR-0226). A rule that serves the objects of several applications of
     * one kind answers for each of them; the default serves {@link #object()} alone.
     */
    default boolean serves(String objectApiName) {
        return object().equals(objectApiName);
    }

    /** Only declared platform-written fields may be contributed. */
    Set<String> writes();

    /**
     * What the rule contributes to a write.
     *
     * @param object the API name of the object being written, so a rule that serves several knows which
     * @param current the record as it is, or null on a create
     */
    Changes prepare(String object, DataRecord current, Map<String, Object> merged, Set<String> editableFields,
            boolean platform);

    default void stored(DataRecord before, DataRecord after) { }

    default Set<String> lockedFields(DataRecord current) {
        return Set.of();
    }

    default String uniqueWords(String field) {
        return null;
    }

    default String lockedWords() {
        return "These fields can no longer be changed.";
    }

    record Changes(Map<String, Object> set, List<String> clear) {
        public Changes {
            set = Map.copyOf(set);
            clear = List.copyOf(clear);
        }

        public static Changes none() {
            return new Changes(Map.of(), List.of());
        }

        @Override
        public String toString() {
            return "Changes[redacted]";
        }
    }
}
