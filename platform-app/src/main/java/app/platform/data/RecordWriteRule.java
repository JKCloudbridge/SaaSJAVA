package app.platform.data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted domain contributions inside the ordinary record pipeline, after member input has passed its checks. */
public interface RecordWriteRule {
    String object();

    /** Only declared platform-written fields may be contributed. */
    Set<String> writes();

    Changes prepare(DataRecord current, Map<String, Object> merged, Set<String> editableFields, boolean platform);

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
