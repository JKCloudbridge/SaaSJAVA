package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.DataRecord;
import app.platform.data.LinkedRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.RecordQuery;
import app.platform.publicapi.PublicBulkItem;
import app.platform.publicapi.PublicBulkOperation;
import app.platform.publicapi.PublicBulkRequest;
import app.platform.publicapi.PublicBulkStatus;
import app.platform.publicapi.PublicFilter;
import app.platform.publicapi.PublicFilterOp;
import app.platform.publicapi.PublicQueryRequest;
import app.platform.publicapi.PublicRecord;
import app.platform.publicapi.PublicResolve;
import app.platform.publicapi.PublicSortKey;
import app.platformapi.ApiException;
import app.platformapi.BulkRecordResult;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import app.platformapi.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shape of the public API in and out (Sprint 30, ADR-0245), without a database: the system block is fixed, the
 * values pass through with the digits they were given, a field that is absent stays absent, and a query or a bulk call
 * becomes exactly the model the record store takes.
 */
class PublicRecordMapperTest {

    private static final Instant CREATED = Instant.parse("2026-10-31T09:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-31T09:45:12.250Z");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private static DataRecord record(Map<String, Object> fields) {
        return new DataRecord(UUID.fromString("0194f2a8-6c1e-7a3b-9d52-3f1c8e2b7a40"), "Employee__c", null,
                UUID.fromString("0194f2a8-1111-7a3b-9d52-3f1c8e2b7a40"), fields, 3, CREATED,
                UUID.randomUUID(), UPDATED, UUID.randomUUID(), null, null);
    }

    @Test
    void aRecordHasTheFixedSystemBlockAndTheValuesKeyedByFieldName() {
        PublicRecord shown = PublicRecordMapper.record(record(Map.of("name__c", "A. Example")));

        assertThat(shown.id()).isEqualTo("0194f2a8-6c1e-7a3b-9d52-3f1c8e2b7a40");
        assertThat(shown.object()).isEqualTo("Employee__c");
        assertThat(shown.version()).isEqualTo(3L);
        assertThat(shown.ownerId()).isEqualTo("0194f2a8-1111-7a3b-9d52-3f1c8e2b7a40");
        assertThat(shown.createdAt()).isEqualTo(CREATED);
        assertThat(shown.updatedAt()).isEqualTo(UPDATED);
        assertThat(shown.fields()).containsExactly(Map.entry("name__c", "A. Example"));
        assertThat(shown.deletedAt()).isNull();
        assertThat(shown.linked()).isNull();
    }

    @Test
    void theRecordShowsTheSystemPropertiesAndNeverTheIdentityOfWhoCreatedOrChangedIt() throws Exception {
        String json = JSON.writeValueAsString(PublicRecordMapper.record(record(Map.of())));

        assertThat(json).contains("\"id\"", "\"object\"", "\"version\"", "\"ownerId\"", "\"createdAt\"",
                "\"updatedAt\"", "\"fields\"");
        assertThat(json).doesNotContain("createdBy", "updatedBy", "createdById", "updatedById", "deletedAt",
                "recordType", "linked");
    }

    @Test
    void aNumberKeepsEveryDigitItWasGiven() {
        BigDecimal exact = new BigDecimal("12345678901234567890.123456789012345678");
        BigDecimal withTrailingZero = new BigDecimal("1250.50");

        PublicRecord shown = PublicRecordMapper.record(record(Map.of("a__c", exact, "b__c", withTrailingZero)));

        assertThat(shown.fields().get("a__c")).isEqualTo(exact);
        assertThat(shown.fields().get("b__c")).isEqualTo(withTrailingZero);
        assertThat(JSON.writeValueAsString(shown)).contains("12345678901234567890.123456789012345678")
                .contains("1250.50");
    }

    @Property
    void aDecimalSurvivesAWholeTripThroughTheMapperWithItsDigitsAndItsScale(
            @ForAll @IntRange(min = 1, max = 18) int whole, @ForAll @IntRange(min = 0, max = 12) int fraction,
            @ForAll @IntRange(min = 1, max = 9) int digit) {
        String typed = digit + "0".repeat(Math.max(0, whole - 1))
                + (fraction == 0 ? "" : "." + "0".repeat(Math.max(0, fraction - 1)) + digit);
        BigDecimal parsed = JSON.readValue("{\"v\":" + typed + "}", BigDecimalHolder.class).v();

        PublicRecord shown = PublicRecordMapper.record(record(Map.of("v__c", parsed)));
        BigDecimal back = JSON.readValue(JSON.writeValueAsString(shown.fields()), BigDecimalHolder2.class).v__c();

        assertThat(back).isEqualTo(parsed);
        assertThat(back.scale()).isEqualTo(parsed.scale());
    }

    /** A holder that reads a JSON number as a decimal. */
    record BigDecimalHolder(BigDecimal v) {
    }

    /** A holder for the field name used by the property. */
    record BigDecimalHolder2(BigDecimal v__c) {
    }

    @Property
    void whatIsAbsentStaysAbsentAndNothingIsAdded(@ForAll @Size(max = 8) List<@net.jqwik.api.constraints.AlphaChars
            @net.jqwik.api.constraints.StringLength(min = 1, max = 20) String> names) {
        Map<String, Object> fields = new LinkedHashMap<>();
        names.forEach(name -> fields.put(name + "__c", "x"));

        PublicRecord shown = PublicRecordMapper.record(record(fields));

        assertThat(shown.fields()).isEqualTo(Map.copyOf(fields));
        assertThat(shown.fields().values()).doesNotContainNull();
    }

    @Test
    void aQueriedRecordCarriesTheLinkedRecordsByFieldName() {
        UUID target = UUID.randomUUID();
        QueriedRecord queried = new QueriedRecord(record(Map.of()),
                Map.of("manager__c", new LinkedRecord(target, Map.of("name__c", "B"))));

        PublicRecord shown = PublicRecordMapper.record(queried);

        assertThat(shown.linked()).containsOnlyKeys("manager__c");
        assertThat(shown.linked().get("manager__c").id()).isEqualTo(target.toString());
        assertThat(shown.linked().get("manager__c").fields()).containsEntry("name__c", "B");
    }

    @Test
    void aQueryBecomesTheClosedModelOfTheRecordStore() {
        PublicQueryRequest body = new PublicQueryRequest(
                new PublicFilter(PublicFilterOp.AND, List.of(
                        new PublicFilter(PublicFilterOp.EQ, null, "department__c", List.of("Sales")),
                        new PublicFilter(PublicFilterOp.BETWEEN, null, "salary__c",
                                List.of(new BigDecimal("1000.50"), 2000))), null, null),
                List.of(new PublicSortKey("name__c", true), new PublicSortKey("salary__c", null)),
                List.of("name__c"), List.of(new PublicResolve("manager__c", List.of("name__c"))), 25, "cur");

        RecordQuery query = PublicRecordMapper.query(body);

        assertThat(query.limit()).isEqualTo(25);
        assertThat(query.cursor()).isEqualTo("cur");
        assertThat(query.fields()).containsExactly("name__c");
        assertThat(query.sort()).hasSize(2);
        assertThat(query.sort().get(0).descending()).isTrue();
        assertThat(query.sort().get(1).descending()).isFalse();
        assertThat(query.resolve().get(0).field()).isEqualTo("manager__c");
        assertThat(query.filter().op()).isEqualTo(QueryFilter.Op.AND);
        assertThat(query.filter().items()).hasSize(2);
        assertThat(query.filter().items().get(1).values()).containsExactly(new BigDecimal("1000.50"), 2000);
    }

    @Test
    void aQueryWithoutAFilterOrALimitAsksForTheDefaults() {
        RecordQuery query = PublicRecordMapper.query(new PublicQueryRequest(null, null, null, null, null, null));

        assertThat(query.filter()).isNull();
        assertThat(query.limit()).isZero();
        assertThat(query.sort()).isEmpty();
        assertThat(query.fields()).isNull();
    }

    @Test
    void aFilterWithAMissingValueIsRefusedWithItsPlace() {
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        PublicQueryRequest body = new PublicQueryRequest(new PublicFilter(PublicFilterOp.AND, List.of(
                new PublicFilter(PublicFilterOp.EQ, null, "name__c", withNull)), null, null),
                null, null, null, null, null);

        assertThatThrownBy(() -> PublicRecordMapper.query(body)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.fields()).containsKey("filter.items[0].values"));
    }

    @Test
    void aFilterThatIsTooLargeOrTooDeepIsRefusedBeforeTheEngineLooksAtIt() {
        PublicFilter deep = new PublicFilter(PublicFilterOp.EQ, null, "name__c", List.of("x"));
        for (int level = 0; level <= PublicRecordMapper.MAX_FILTER_DEPTH; level++) {
            deep = new PublicFilter(PublicFilterOp.AND, List.of(deep), null, null);
        }
        PublicFilter tooDeep = deep;

        assertThatThrownBy(() -> PublicRecordMapper.query(new PublicQueryRequest(tooDeep, null, null, null, null,
                null))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR));

        List<PublicFilter> many = new ArrayList<>();
        for (int index = 0; index <= PublicRecordMapper.MAX_FILTER_NODES; index++) {
            many.add(new PublicFilter(PublicFilterOp.EQ, null, "name__c", List.of("x")));
        }
        PublicFilter wide = new PublicFilter(PublicFilterOp.OR, many, null, null);
        assertThatThrownBy(() -> PublicRecordMapper.query(new PublicQueryRequest(wide, null, null, null, null,
                null))).isInstanceOf(ApiException.class);
    }

    @Test
    void aBulkCallBecomesTheInternalCallWithTheSameItemsInTheSameOrder() {
        UUID id = UUID.randomUUID();
        PublicBulkRequest body = new PublicBulkRequest(PublicBulkOperation.UPDATE, true, List.of(
                new PublicBulkItem(id, 4L, null, Map.of("name__c", "x"), List.of("notes__c")),
                new PublicBulkItem(UUID.randomUUID(), 1L, "t", Map.of(), null)));

        BulkRecordsRequest request = PublicRecordMapper.bulk(body);

        assertThat(request.operation()).isEqualTo("UPDATE");
        assertThat(request.allOrNone()).isTrue();
        assertThat(request.records()).hasSize(2);
        assertThat(request.records().get(0).id()).isEqualTo(id);
        assertThat(request.records().get(0).version()).isEqualTo(4L);
        assertThat(request.records().get(0).clear()).containsExactly("notes__c");
        assertThat(request.records().get(1).recordType()).isEqualTo("t");
    }

    @Test
    void aBulkAnswerSaysForEachPositionWhatHappenedWithTheStableErrorOfAFailure() {
        UUID created = UUID.randomUUID();
        BulkRecordsResponse response = new BulkRecordsResponse("CREATE", false, true, 1, 1, List.of(
                new BulkRecordResult(0, "CREATED", created, 0L, null, null, null),
                new BulkRecordResult(1, "FAILED", null, null, "VALIDATION_ERROR", "The request is invalid.",
                        Map.of("fields.qty__c", List.of("Enter a number."))),
                new BulkRecordResult(2, "ROLLED_BACK", null, null, null, null, null)));

        var shown = PublicRecordMapper.bulk(response);

        assertThat(shown.operation()).isEqualTo(PublicBulkOperation.CREATE);
        assertThat(shown.results()).extracting(result -> result.position()).containsExactly(0, 1, 2);
        assertThat(shown.results()).extracting(result -> result.status())
                .containsExactly(PublicBulkStatus.CREATED, PublicBulkStatus.FAILED, PublicBulkStatus.ROLLED_BACK);
        assertThat(shown.results().get(0).id()).isEqualTo(created.toString());
        assertThat(shown.results().get(0).error()).isNull();
        assertThat(shown.results().get(1).error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(shown.results().get(1).error().fields()).containsKey("fields.qty__c");
        assertThat(shown.results().get(1).id()).isNull();
    }
}
