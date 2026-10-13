package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryPlan;
import app.platform.data.QueryResult;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platformapi.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The search over the records of one object (Sprint 17, ADR-0124): the words of a search are looked for in the words of
 * the searchable fields, a record must have all of them, the last word typed counts as a beginning, and the best
 * matches come first.
 *
 * <p>Everything a list is held to, a search is held to by the same code. The fields searched are the searchable
 * fields <em>the member may read</em> (a record never matches on a field the member cannot see, so the search cannot be
 * used to learn what a field holds), and only those whose words are complete. The records are the ones the member may
 * reach ({@link RecordVisibility}, the same condition as in a list, in the same statement). What comes back is shaped
 * and filtered exactly like the answer of a query ({@link FieldSecurity}); nothing is highlighted.
 *
 * <p>The cost is bounded. The engine first asks, for each word, how many records have it, reading at most the
 * configured number of index entries; it reads the rarest word first and checks the others against its records, so a
 * search costs about as much as the rarest word has records, and a search all of whose words are more common than the
 * bound is refused with the advice to type more.
 */
@Component
class SearchEngine {

    private final SearchStore store;
    private final StorageStrategy storage;
    private final RecordVisibility visibility;
    private final SearchProperties settings;
    private final RecordProperties.Query limits;

    SearchEngine(SearchStore store, StorageStrategy storage, RecordVisibility visibility, SearchProperties settings,
            RecordProperties properties) {
        this.store = store;
        this.storage = storage;
        this.visibility = visibility;
        this.settings = settings;
        this.limits = properties.query();
    }

    /** Runs a search for a member who passed the gate. */
    QueryResult search(RecordGate.Passed passed, ObjectAccess access, String text, int requestedLimit, String cursor) {
        ObjectDefinition object = passed.object();
        int limit = requestedLimit <= 0 ? limits.defaultPageSize() : Math.min(requestedLimit, limits.maxPageSize());
        List<String> fields = searchedFields(object, access);
        if (fields.isEmpty()) {
            throw ApiException.validation("text", "No field of this object is searchable by you, or the search of "
                    + "its fields is not ready yet.");
        }
        List<SearchTokens.Word> words = SearchTokens.queryWords(text, settings.maxQueryWords());
        if (words.isEmpty()) {
            throw ApiException.validation("text", "Type at least two letters, or a number.");
        }
        List<Counted> counted = new ArrayList<>();
        for (SearchTokens.Word word : words) {
            int found = store.count(object.apiName(), word.text(), word.beginning(), word.upperBound(), fields,
                    settings.maxCandidates());
            if (found == 0) {
                return new QueryResult(List.of(), null, limit);
            }
            counted.add(new Counted(word, found));
        }
        Counted rarest = counted.stream().min(Comparator.comparingInt(Counted::found)).orElseThrow();
        if (rarest.found() > settings.maxCandidates()) {
            throw ApiException.validation("text", "Too many records match this search. Type more of the word, or "
                    + "another word.");
        }
        List<StorageStrategy.SearchWord> others = counted.stream().filter(one -> one != rarest)
                .map(one -> word(one.word())).toList();
        QueryPlan.Node constraint = visibility.constraintFor(passed, access).orElse(null);
        Position after = cursor == null ? null : decode(cursor);
        List<StorageStrategy.ScoredRecord> rows = storage.search(new StorageStrategy.SearchPlan(object.apiName(),
                word(rarest.word()), others, fields, constraint, after == null ? null : after.score(),
                after == null ? null : after.id(), limit + 1));
        boolean more = rows.size() > limit;
        List<StorageStrategy.ScoredRecord> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            StorageStrategy.ScoredRecord last = page.get(page.size() - 1);
            next = encode(last.score(), last.record().id());
        }
        List<QueriedRecord> records = new ArrayList<>();
        for (StorageStrategy.ScoredRecord row : page) {
            DataRecord visible = FieldSecurity.visible(row.record(), access);
            records.add(new QueriedRecord(visible, Map.of()));
        }
        return new QueryResult(records, next, limit);
    }

    /** The searchable fields of the object that the member may read and whose words are complete, best first. */
    private List<String> searchedFields(ObjectDefinition object, ObjectAccess access) {
        Set<String> complete = store.fields().stream()
                .filter(row -> row.objectApiName().equals(object.apiName()) && row.state() == SearchStore.State.FILLED)
                .map(SearchStore.Field::fieldApiName).collect(Collectors.toSet());
        return SearchIndexer.searchable(object).stream().map(FieldDefinition::apiName)
                .filter(name -> access.readableFields().contains(name) && complete.contains(name)).toList();
    }

    private static StorageStrategy.SearchWord word(SearchTokens.Word word) {
        return new StorageStrategy.SearchWord(word.text(), word.beginning(), word.upperBound());
    }

    private record Counted(SearchTokens.Word word, int found) {
    }

    private record Position(int score, UUID id) {
    }

    private static String encode(int score, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((score + ":" + id)
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static Position decode(String cursor) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int colon = text.indexOf(':');
            return new Position(Integer.parseInt(text.substring(0, colon)),
                    UUID.fromString(text.substring(colon + 1)));
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            throw ApiException.validation("cursor", "Is not a valid cursor");
        }
    }
}
