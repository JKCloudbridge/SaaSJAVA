package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Flagging a field is a speed decision and never a meaning decision (Sprint 16, ADR-0103): the same records and the
 * same questions are asked before the fields are indexed (every record is read) and after (the slots and their indexes
 * are used), for every operator on every indexable kind, including whole numbers that a double cannot tell apart,
 * records without a value, days and times, a record in the recycle bin and a restored one. The answers, and the pages
 * of the sorted ones, must be identical.
 */
@PlatformIntegrationTest
class QueryDifferentialIT extends QuerySupport {

    private static final String BIG = "9007199254740992";
    private static final String BIGGER = "9007199254740993";

    private String target;

    private void data(TestBrowser admin) {
        target = idOf(admin.postJson(TARGETS, "{\"fields\":{}}"));
        String other = idOf(admin.postJson(TARGETS, "{\"fields\":{}}"));
        List<String> names = List.of("alpha", "Alpha", "beta", "gamma", "al%pha");
        List<String> quantities = List.of(BIG, BIGGER, "-5", "0", "100");
        List<String> prices = List.of("0.1", "0.100001", "1234.5", "-0.5");
        for (int i = 0; i < 30; i++) {
            List<String> parts = new ArrayList<>();
            if (i % 7 != 6) {
                parts.add("\"name__c\":\"" + names.get(i % names.size()) + "\"");
            }
            parts.add("\"code__c\":\"c" + i + "\"");
            if (i % 6 != 5) {
                parts.add("\"qty__c\":" + quantities.get(i % quantities.size()));
            }
            if (i % 5 != 4) {
                parts.add("\"price__c\":" + prices.get(i % prices.size()));
            }
            if (i % 4 != 3) {
                parts.add("\"due__c\":\"2026-0" + (1 + i % 9) + "-1" + (i % 9) + "\"");
            }
            if (i % 8 != 7) {
                parts.add("\"seen__c\":\"2026-03-0" + (1 + i % 9) + "T1" + (i % 10) + ":30:00Z\"");
            }
            if (i % 3 != 2) {
                parts.add("\"done__c\":" + (i % 2 == 0));
            }
            if (i % 9 != 8) {
                parts.add("\"stage__c\":\"" + "abc".charAt(i % 3) + "\"");
                parts.add("\"link__c\":\"" + (i % 2 == 0 ? target : other) + "\"");
            }
            parts.add("\"mail__c\":\"user" + i % 10 + "@example.test\"");
            put(admin, "{" + String.join(",", parts) + "}");
        }
        String deleted = put(admin, "{\"name__c\":\"alpha\",\"qty__c\":100}");
        admin.request("DELETE", ITEM_RECORDS + "/" + deleted, null);
        String restored = put(admin, "{\"name__c\":\"alpha\",\"qty__c\":100}");
        admin.request("DELETE", ITEM_RECORDS + "/" + restored, null);
        admin.postJson(ITEM_BIN + "/" + restored + "/restore", "{}");
    }

    private Map<String, String> questions() {
        Map<String, String> asked = new LinkedHashMap<>();
        String[] texts = {"name__c", "stage__c", "mail__c"};
        for (String field : texts) {
            String value = field.equals("stage__c") ? "\"b\"" : field.equals("mail__c") ? "\"user3@example.test\""
                    : "\"alpha\"";
            for (String op : List.of("EQ", "NE", "GT", "GTE", "LT", "LTE")) {
                asked.put(field + " " + op, ask(on(field, op, value), null, 200));
            }
            asked.put(field + " BETWEEN", ask(on(field, "BETWEEN", value, "\"c\""), null, 200));
            asked.put(field + " IN", ask(on(field, "IN", value, "\"gamma\"", "\"nothing\""), null, 200));
            asked.put(field + " IS_EMPTY", ask(on(field, "IS_EMPTY"), null, 200));
            asked.put(field + " IS_NOT_EMPTY", ask(on(field, "IS_NOT_EMPTY"), null, 200));
        }
        asked.put("starts", ask(group("AND", on("code__c", "IS_NOT_EMPTY"), on("name__c", "STARTS_WITH", "\"al\"")),
                null, 200));
        asked.put("contains", ask(group("AND", on("code__c", "IS_NOT_EMPTY"), on("name__c", "CONTAINS", "\"%\"")),
                null, 200));
        for (String field : List.of("qty__c", "price__c")) {
            String value = field.equals("qty__c") ? BIG : "0.1";
            String near = field.equals("qty__c") ? BIGGER : "0.100001";
            for (String op : List.of("EQ", "NE", "GT", "GTE", "LT", "LTE")) {
                asked.put(field + " " + op, ask(on(field, op, value), null, 200));
                asked.put(field + " " + op + " near", ask(on(field, op, near), null, 200));
            }
            asked.put(field + " BETWEEN", ask(on(field, "BETWEEN", value, near), null, 200));
            asked.put(field + " IN", ask(on(field, "IN", value, near), null, 200));
            asked.put(field + " IS_EMPTY", ask(on(field, "IS_EMPTY"), null, 200));
        }
        for (String op : List.of("EQ", "NE", "GT", "GTE", "LT", "LTE")) {
            asked.put("due " + op, ask(on("due__c", op, "\"2026-03-12\""), null, 200));
            asked.put("seen " + op, ask(on("seen__c", op, "\"2026-03-03T12:30:00Z\""), null, 200));
        }
        asked.put("due BETWEEN", ask(on("due__c", "BETWEEN", "\"2026-02-01\"", "\"2026-05-30\""), null, 200));
        asked.put("seen offset", ask(on("seen__c", "EQ", "\"2026-03-03T14:30:00+02:00\""), null, 200));
        asked.put("seen EMPTY", ask(on("seen__c", "IS_EMPTY"), null, 200));
        asked.put("done true", ask(on("done__c", "EQ", "true"), null, 200));
        asked.put("done false", ask(on("done__c", "EQ", "false"), null, 200));
        asked.put("done NE", ask(on("done__c", "NE", "true"), null, 200));
        asked.put("done EMPTY", ask(on("done__c", "IS_EMPTY"), null, 200));
        asked.put("link EQ", ask(on("link__c", "EQ", "\"" + target + "\""), null, 200));
        asked.put("link IN", ask(on("link__c", "IN", "\"" + target + "\""), null, 200));
        asked.put("link NE", ask(on("link__c", "NE", "\"" + target + "\""), null, 200));
        asked.put("link EMPTY", ask(on("link__c", "IS_EMPTY"), null, 200));
        asked.put("and", ask(group("AND", on("name__c", "EQ", "\"alpha\""), on("qty__c", "GTE", "0")), null, 200));
        asked.put("or", ask(group("OR", on("name__c", "EQ", "\"beta\""), on("done__c", "EQ", "true")), null, 200));
        return asked;
    }

    private Map<String, List<String>> answers(TestBrowser admin) {
        Map<String, List<String>> all = new LinkedHashMap<>();
        questions().forEach((name, body) -> all.put(name, ids(query(admin, body))));
        for (String field : List.of("name__c", "qty__c", "price__c", "due__c", "seen__c", "stage__c", "mail__c")) {
            for (boolean descending : List.of(false, true)) {
                all.put("pages by " + field + " " + descending,
                        allPages(admin, null, by(field, descending), 7));
            }
        }
        return all;
    }

    @Test
    void indexedAndUnindexedPathsGiveIdenticalAnswersForEveryOperatorAndOrder() {
        Items items = items();
        TestBrowser admin = items.admin();
        data(admin);

        Map<String, List<String>> before = answers(admin);
        flagAll(admin, "name", "code", "mail", "stage", "qty", "price", "due", "seen", "done", "link");
        settle(items.organization(), admin);
        Map<String, List<String>> after = answers(admin);

        assertThat(before.get("qty__c EQ")).as("the double cannot tell these two apart, the record can")
                .isNotEmpty().isNotEqualTo(before.get("qty__c EQ near"));
        before.forEach((name, expected) -> assertThat(after.get(name)).as(name).isEqualTo(expected));
    }
}
