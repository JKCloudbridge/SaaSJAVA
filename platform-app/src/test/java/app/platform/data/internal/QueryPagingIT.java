package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Keyset paging with ties and records that have no value (Sprint 16, ADR-0104): one and two keys, both directions,
 * with and without an index, compared with the order computed here from the same data; no record twice and none
 * missing, also while other records are written.
 */
@PlatformIntegrationTest
class QueryPagingIT extends QuerySupport {

    /** One record as the test knows it: the identifier and the values of the two keys (null: no value). */
    private record Row(String id, String stage, Integer qty) {
    }

    private List<Row> data(TestBrowser admin) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String stage = i % 5 == 4 ? null : String.valueOf("abc".charAt(i % 3));
            Integer qty = i % 7 == 6 ? null : (i * 3) % 5;
            List<String> parts = new ArrayList<>();
            if (stage != null) {
                parts.add("\"stage__c\":\"" + stage + "\"");
            }
            if (qty != null) {
                parts.add("\"qty__c\":" + qty);
            }
            rows.add(new Row(put(admin, "{" + String.join(",", parts) + "}"), stage, qty));
        }
        return rows;
    }

    /** The order the platform promises: nulls last in both directions, then the identifier like the last key. */
    private static List<String> expected(List<Row> rows, boolean stageFirst, boolean descending, boolean second,
            boolean secondDescending) {
        Comparator<Row> order = (a, b) -> 0;
        Comparator<Row> one = stageFirst ? byStage(descending) : byQty(descending);
        order = one;
        boolean lastDescending = descending;
        if (second) {
            order = order.thenComparing(stageFirst ? byQty(secondDescending) : byStage(secondDescending));
            lastDescending = secondDescending;
        }
        Comparator<Row> ids = Comparator.comparing(Row::id);
        order = order.thenComparing(lastDescending ? ids.reversed() : ids);
        return rows.stream().sorted(order).map(Row::id).toList();
    }

    private static Comparator<Row> byStage(boolean descending) {
        return nullsLast(Row::stage, descending);
    }

    private static Comparator<Row> byQty(boolean descending) {
        return nullsLast(Row::qty, descending);
    }

    private static <T extends Comparable<T>> Comparator<Row> nullsLast(java.util.function.Function<Row, T> key,
            boolean descending) {
        return (a, b) -> {
            T left = key.apply(a);
            T right = key.apply(b);
            if (left == null || right == null) {
                return left == null ? (right == null ? 0 : 1) : -1;
            }
            return descending ? right.compareTo(left) : left.compareTo(right);
        };
    }

    private static String keys(boolean stageFirst, boolean descending, boolean second, boolean secondDescending) {
        String first = "{\"field\":\"" + (stageFirst ? "stage__c" : "qty__c") + "\",\"descending\":" + descending + "}";
        if (!second) {
            return "[" + first + "]";
        }
        return "[" + first + ",{\"field\":\"" + (stageFirst ? "qty__c" : "stage__c") + "\",\"descending\":"
                + secondDescending + "}]";
    }

    private void everyOrderPagesExactly(boolean indexed) {
        Items items = items();
        TestBrowser admin = items.admin();
        List<Row> rows = data(admin);
        if (indexed) {
            flagAll(admin, "stage", "qty");
            settle(items.organization(), admin);
        }
        Map<String, List<String>> got = new LinkedHashMap<>();
        for (boolean stageFirst : List.of(true, false)) {
            for (boolean descending : List.of(false, true)) {
                for (boolean second : List.of(false, true)) {
                    for (boolean secondDescending : List.of(false, true)) {
                        if (!second && secondDescending) {
                            continue;
                        }
                        String label = stageFirst + " " + descending + " " + second + " " + secondDescending;
                        got.put(label, allPages(admin, null, keys(stageFirst, descending, second, secondDescending),
                                6));
                        assertThat(got.get(label)).as(label + " indexed=" + indexed)
                                .isEqualTo(expected(rows, stageFirst, descending, second, secondDescending));
                    }
                }
            }
        }
    }

    @Test
    void everyOrderPagesExactlyWithoutAnIndex() {
        everyOrderPagesExactly(false);
    }

    @Test
    void everyOrderPagesExactlyWithTheIndexes() {
        everyOrderPagesExactly(true);
    }

    @Test
    void recordsThatDoNotChangeAreSeenOnceAndInOrderWhileOthersAreWritten() throws Exception {
        Items items = items();
        TestBrowser admin = items.admin();
        List<Row> rows = data(admin);
        flagAll(admin, "stage", "qty");
        settle(items.organization(), admin);
        AtomicBoolean stop = new AtomicBoolean();
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            int i = 0;
            while (!stop.get() && i < 200) {
                put(admin, "{\"stage__c\":\"" + "abc".charAt(i % 3) + "\",\"qty__c\":" + (i % 5) + "}");
                i++;
            }
        });

        List<String> seen = allPages(admin, null, keys(true, false, true, true), 5);
        stop.set(true);
        writer.get();

        assertThat(seen).as("no record twice").doesNotHaveDuplicates();
        List<String> stable = seen.stream().filter(id -> rows.stream().anyMatch(row -> row.id().equals(id))).toList();
        assertThat(stable).as("none of the older ones missing, in order")
                .isEqualTo(expected(rows, true, false, true, true));
    }
}
