package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The query endpoint (Sprint 16, ADR-0100, ADR-0105, ADR-0107): allowed, denied, cross-tenant, a forged host, a member
 * who may read the object but not a field of the query, the refusal on a large object without an index, projection,
 * lookups and the stages' order.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = "platform.data.query.unindexed-scan-limit=5")
class QueryEndpointIT extends QuerySupport {

    private static final String SECRET_FIELDS = "[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]},"
            + "{\"key\":\"Item__c.code__c\",\"actions\":[\"read\"]}]";

    @Test
    void aMemberFiltersSortsAndPagesAndTheAnswerHasNoTotal() {
        Items items = items();
        TestBrowser admin = items.admin();
        for (String name : List.of("c", "a", "b")) {
            put(admin, "{\"name__c\":\"" + name + "\"}");
        }

        Response first = query(admin, ask(on("name__c", "GTE", "\"a\""), by("name__c", false), 2));

        assertThat(JsonPath.<List<String>>read(first.body(), "$.data[*].record.fields.name__c"))
                .containsExactly("a", "b");
        assertThat(hasMore(first)).isTrue();
        assertThat(first.body()).doesNotContain("total");
        Response last = query(admin, ask(on("name__c", "GTE", "\"a\""), by("name__c", false), 2)
                .replace("\"limit\":2", "\"limit\":2,\"cursor\":\"" + next(first) + "\""));
        assertThat(JsonPath.<List<String>>read(last.body(), "$.data[*].record.fields.name__c")).containsExactly("c");
        assertThat(hasMore(last)).isFalse();
    }

    @Test
    void aCursorOfAnotherQueryIsRefused() {
        Items items = items();
        TestBrowser admin = items.admin();
        for (String name : List.of("a", "b", "c")) {
            put(admin, "{\"name__c\":\"" + name + "\"}");
        }
        Response first = query(admin, ask(null, by("name__c", false), 1));

        Response other = query(admin, ask(null, by("name__c", true), 1).replace("\"limit\":1",
                "\"limit\":1,\"cursor\":\"" + next(first) + "\""));

        assertThat(other.status()).isEqualTo(400);
        assertThat(problemAt(other, "cursor")).isNotNull();
    }

    @Test
    void aMemberWhoMayReadTheObjectButNotAFieldGetsTheAnswerForAFieldThatDoesNotExist() {
        Items items = items();
        TestBrowser admin = items.admin();
        put(admin, "{\"name__c\":\"a\",\"code__c\":\"secret-code\"}");
        Holder holder = holderWith(items.organization(), admin, "Item__c", SECRET_FIELDS, "read");
        TestBrowser member = as(items.organization(), holder.member());

        Response forbidden = query(member, ask(on("qty__c", "CONTAINS", "\"x\""), null, 10));
        Response unknown = query(member, ask(on("nothing__c", "CONTAINS", "\"x\""), null, 10));
        Response sorted = query(member, ask(null, by("qty__c", false), 10));
        Response projected = query(member, "{\"fields\":[\"qty__c\"]}");
        Response fine = query(member, ask(null, null, 10));

        for (Response refused : List.of(forbidden, unknown, sorted, projected)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        }
        assertThat(forbidden.body().replace("filter.field", "")).contains("This is not a field of the object.");
        assertThat(problemAt(forbidden, "filter.field")).isEqualTo(problemAt(unknown, "filter.field"));
        assertThat(JsonPath.<Map<String, Object>>read(fine.body(), "$.data[0].record.fields").keySet())
                .containsExactlyInAnyOrder("name__c", "code__c");
    }

    @Test
    void aMemberWhoCannotReadTheObjectGetsNotFoundLikeAnUnknownObject() {
        Items items = items();
        TestBrowser stranger = as(items.organization(), memberWithActions(items.organization(), items.admin()));

        Response denied = query(stranger, "{}");
        Response unknown = stranger.postJson("/api/v1/objects/Nothing__c/records/query", "{}");

        assertThat(denied.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(withoutIdentifiers(denied.body()))
                .isEqualTo(withoutIdentifiers(unknown.body().replace("Nothing__c", "Item__c")));
    }

    @Test
    void anotherOrganizationNeverSeesTheRecordsAndAForgedHostChangesNothing() {
        Items mine = items();
        Items theirs = items();
        put(mine.admin(), "{\"name__c\":\"mine\"}");
        put(theirs.admin(), "{\"name__c\":\"theirs\"}");

        Response seen = query(theirs.admin(), "{}");
        Response forged = theirs.admin().postJson(ITEM_QUERY, "{}", "X-Forwarded-Host", mine.organization().host(),
                "X-Tenant-Id", mine.organization().id().toString());

        assertThat(JsonPath.<List<String>>read(seen.body(), "$.data[*].record.fields.name__c"))
                .containsExactly("theirs");
        assertThat(forged.body()).doesNotContain("mine");
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        Items items = items();

        Response anonymous = new TestBrowser(port, items.organization().host()).postJson(ITEM_QUERY, "{}");

        assertThat(anonymous.status()).isEqualTo(401);
    }

    @Test
    void aFilterWithoutAnIndexOnALargeObjectIsRefusedWithTheFieldToFlag() {
        Items items = items();
        TestBrowser admin = items.admin();
        for (int i = 0; i < 8; i++) {
            put(admin, "{\"name__c\":\"n" + i + "\",\"code__c\":\"c" + i + "\"}");
        }

        Response plain = query(admin, ask(on("name__c", "EQ", "\"n1\""), null, 10));
        Response sorted = query(admin, ask(null, by("name__c", false), 10));
        Response search = query(admin, ask(on("name__c", "CONTAINS", "\"n\""), null, 10));
        Response narrowed = query(admin, ask(group("AND", on("createdAt", "GTE", "\"2020-01-01T00:00:00Z\""),
                on("name__c", "EQ", "\"n1\"")), null, 10));

        assertThat(plain.status()).isEqualTo(400);
        assertThat(problemAt(plain, "query")).contains("flag name__c as indexed");
        assertThat(problemAt(sorted, "query")).contains("name__c");
        assertThat(problemAt(search, "query")).contains("cannot use an index");
        assertThat(ids(narrowed)).as("another, indexed condition narrows the records first").hasSize(1);

        flagAll(admin, "name");
        Response preparing = query(admin, ask(on("name__c", "EQ", "\"n1\""), null, 10));
        assertThat(problemAt(preparing, "query")).contains("not complete yet");
        settle(items.organization(), admin);
        assertThat(ids(query(admin, ask(on("name__c", "EQ", "\"n1\""), null, 10)))).hasSize(1);
    }

    @Test
    void aFormulaALongTextAndALinkCannotBeFilteredOrSortedAndTheReasonIsGiven() {
        Items items = items();

        Response formula = query(items.admin(), ask(on("calc__c", "GT", "1"), null, 10));
        Response notes = query(items.admin(), ask(on("notes__c", "EQ", "\"x\""), null, 10));
        Response link = query(items.admin(), ask(null, by("link__c", false), 10));

        assertThat(problemAt(formula, "filter.field")).as("no pass has finished yet").contains("brought up to date");
        assertThat(problemAt(notes, "filter.field")).contains("cannot be used");
        assertThat(problemAt(link, "sort[0].field")).contains("link");
    }

    @Test
    void theProjectionLimitsTheFieldsAndALinkBringsTheChosenFieldsOfTheLinkedRecord() {
        Items items = items();
        TestBrowser admin = items.admin();
        String target = idOf(admin.postJson(TARGETS, "{\"fields\":{}}"));
        String id = put(admin, "{\"name__c\":\"a\",\"code__c\":\"b\",\"link__c\":\"" + target + "\"}");
        String gone = idOf(admin.postJson(TARGETS, "{\"fields\":{}}"));
        String orphan = put(admin, "{\"name__c\":\"o\",\"link__c\":\"" + gone + "\"}");
        admin.request("DELETE", TARGETS + "/" + gone, null);

        Response answer = query(admin, "{\"fields\":[\"name__c\"],\"resolve\":[{\"field\":\"link__c\","
                + "\"fields\":[\"id\",\"nothing__c\"]}],\"sort\":" + by("createdAt", false) + "}");

        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data[*].record.id")).contains(id, orphan);
        assertThat(JsonPath.<Map<String, Object>>read(answer.body(), "$.data[0].record.fields").keySet())
                .containsExactly("name__c");
        assertThat(JsonPath.<String>read(answer.body(), "$.data[0].linked['link__c'].id")).isEqualTo(target);
        assertThat(JsonPath.<Map<String, Object>>read(answer.body(), "$.data[1].linked"))
                .as("removing the target cleared the pointer, so there is nothing to show").isEmpty();
    }

    @Test
    void theStagesAnswerInOrderAndTheFirstThatFindsProblemsAnswersAlone() {
        Items items = items();

        Response shape = query(items.admin(), "{\"limit\":5,\"filter\":{\"op\":\"AND\",\"items\":[]},\"sort\":"
                + "[{\"field\":\"nothing__c\"}]}");

        assertThat(shape.status()).isEqualTo(400);
        assertThat(problemAt(shape, "filter")).isNotNull();
        assertThat(problemAt(shape, "sort[0].field")).as("the fields come after the shape").isNull();
    }

    @Test
    void hostileNamesAndValuesAreOnlyData() {
        Items items = items();
        TestBrowser admin = items.admin();
        put(admin, "{\"name__c\":\"x\"}");

        Response name = query(admin, ask(on("name__c' or '1'='1", "EQ", "\"x\""), null, 10));
        Response value = query(admin, ask(group("AND", on("createdAt", "GTE", "\"2020-01-01T00:00:00Z\""),
                on("name__c", "EQ", "\"x' or '1'='1\"")), null, 10));
        Response percent = query(admin, ask(group("AND", on("createdAt", "GTE", "\"2020-01-01T00:00:00Z\""),
                on("name__c", "STARTS_WITH", "\"%\"")), null, 10));

        assertThat(name.status()).isEqualTo(400);
        assertThat(ids(value)).isEmpty();
        assertThat(ids(percent)).as("a percent sign is only text").isEmpty();
        assertThat(ids(query(admin, "{}"))).hasSize(1);
    }

    /** The answer without the identifiers that differ by request (the request and trace identifiers). */
    private static String withoutIdentifiers(String body) {
        return body.replaceAll("\"(requestId|traceId)\":\"[^\"]*\"", "");
    }
}
