package app.platform.data.internal;

import app.platform.data.QueryFilter;
import app.platform.data.QueryFilter.Op;
import app.platform.data.QueryPlan.After;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the scenarios of the benchmark that go through the production code (Sprint 16, ADR-0109): the real analyzer,
 * the real planner and the real statement builder turn a question about the benchmark's {@code Employee__c} object
 * into the statement the application would send, and the statements of an update and an insert are the application's
 * own constants. This class only gives the parameters their random values (the way the hand-written scenarios of
 * Sprint 14 did) and wraps the statement in the transaction the benchmark measures. Not a test: run by
 * {@code benchmark/run-benchmark.ps1} after {@code test-compile}. Usage: {@code BenchmarkStatements <folder>}.
 */
public final class BenchmarkStatements {

    private static final Pattern PLACEHOLDER = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");
    private static final String NIL = "'00000000-0000-0000-0000-000000000000'";
    private static final String SETTINGS = "begin;\nselect set_config('application_name', 'bench', true);\n"
            + "select set_config('app.current_tenant', bench_tenant(1)::text, true);\n";

    private final QueryAnalyzer analyzer;
    private final QueryPlanner planner = new QueryPlanner();
    private final ObjectDefinition employee = employee();
    private final ObjectAccess access;
    private final Map<String, SlotColumn> complete = new LinkedHashMap<>();

    private BenchmarkStatements() {
        RecordProperties settings = new RecordProperties(new RecordProperties.Limits(262_144),
                new RecordProperties.Retention(true, Duration.ofDays(30), Duration.ofHours(1), 500),
                new RecordProperties.Index(true, 1000, 100, Duration.ofMinutes(5)),
                new RecordProperties.Query(50, 200, 4, 50, 100, 100, 20_000),
                new RecordProperties.Links(10_000, 1000));
        analyzer = new QueryAnalyzer(settings);
        Set<String> readable = new HashSet<>();
        employee.fields().forEach(field -> readable.add(field.apiName()));
        access = new ObjectAccess("Employee__c", EnumSet.of(ObjectAction.READ), readable, Set.of());
        // The slots the loader fills (benchmark/sql/10-load.sql): the same fields the Sprint 14 scenarios used.
        complete.put("Department__c", SlotColumn.TEXT_1);
        complete.put("Email__c", SlotColumn.TEXT_2);
        complete.put("Salary__c", SlotColumn.NUM_1);
        complete.put("HireDate__c", SlotColumn.DATE_1);
        complete.put("Manager__c", SlotColumn.REF_1);
    }

    private static FieldDefinition field(String name, FieldType type, FieldConfiguration configuration) {
        return new FieldDefinition("Employee__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                configuration, false, 0, false);
    }

    private static ObjectDefinition employee() {
        List<FieldDefinition> fields = new ArrayList<>();
        fields.add(new FieldDefinition("Employee__c", "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true,
                true, null, new TextConfiguration(36), false, 0));
        fields.add(field("Department__c", FieldType.TEXT, new TextConfiguration(255)));
        fields.add(field("Email__c", FieldType.EMAIL, new TextConfiguration(254)));
        fields.add(field("Salary__c", FieldType.NUMBER, new NumberConfiguration(18)));
        fields.add(field("HireDate__c", FieldType.DATE, new NoConfiguration()));
        fields.add(field("Manager__c", FieldType.LOOKUP, new ReferenceConfiguration("Employee__c")));
        fields.add(field("Stage__c", FieldType.TEXT, new TextConfiguration(255)));
        fields.add(field("Code__c", FieldType.TEXT, new TextConfiguration(255)));
        return new ObjectDefinition("Employee__c", "Employee", "Employees", "", DefinitionKind.CUSTOM, null, true, 0,
                fields);
    }

    /** The statement with every named parameter replaced: by the SQL given, or by the value the builder bound. */
    private static String fill(String statement, Map<String, Object> bound, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(statement);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = bound.get(name);
            String replacement = values.containsKey(name) ? values.get(name)
                    : value == null ? "NULL" : "'" + value.toString().replace("'", "''") + "'";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** One scenario: the first phase of the page the question needs, with every parameter replaced. */
    private String scenario(String title, String header, QueryFilter filter, List<QuerySort> sort, After after,
            Map<String, String> values) {
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(employee, access,
                new RecordQuery(filter, sort, null, List.of(), 50, null));
        JsonbQuerySql.Built built = JsonbQuerySql.build(planner.plan(analyzed, complete, null, after, 50).plan());
        JsonbQuerySql.Phase phase = built.phases().get(0);
        String body = "select id, version, payload from object_record where tenant_id = bench_tenant(1) and "
                + "object_api_name = 'Employee__c' and deleted_at is null and " + built.filter() + " and ("
                + phase.where() + ") order by " + phase.order() + " limit 50";
        return "-- " + title + " (generated from the production planner and statement builder)\n" + header
                + SETTINGS + fill(body, built.params(), values) + ";\ncommit;\n";
    }

    private static QueryFilter eq(String field, String value) {
        return QueryFilter.on(field, Op.EQ, value);
    }

    private Map<String, String> queries() {
        Map<String, String> files = new LinkedHashMap<>();
        After fromRandom = new After(List.of(), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String department = "('Dept ' || lpad((:d)::text, 2, '0'))";
        files.put("s03a-filter-text", scenario("S3 text: indexed text filter, keyset page of 50",
                "\\set d random(0, 49)\n\\set i random(1, :big_n)\n", eq("Department__c", "x"), List.of(), fromRandom,
                Map.of("p0", department, "aid", "bench_id(:i)")));
        files.put("s03b-filter-unique-text", scenario("S3 unique text: one record by its e-mail",
                "\\set i random(1, :big_n)\n", eq("Email__c", "x"), List.of(), null,
                Map.of("p0", "('user' || lpad((:i)::text, 9, '0') || '@example.test')")));
        files.put("s03c-filter-number", scenario("S3 number: indexed number range",
                "\\set s random(30000, 116000)\n\\set i random(1, :big_n)\n",
                QueryFilter.on("Salary__c", Op.BETWEEN, 1, 2), List.of(), fromRandom,
                Map.of("p0", "(:s)", "p1", "(:s + 2000)", "aid", "bench_id(:i)")));
        files.put("s03d-filter-date", scenario("S3 date: indexed day range", "\\set i random(1, :big_n)\n",
                QueryFilter.on("HireDate__c", Op.BETWEEN, "2020-01-01", "2020-03-01"), List.of(), fromRandom,
                Map.of("aid", "bench_id(:i)")));
        files.put("s03e-filter-lookup", scenario("S3 lookup: records that point at one record",
                "\\set i random(1, :big_n)\n\\set j random(1, :big_n)\n",
                eq("Manager__c", "00000000-0000-0000-0000-000000000001"), List.of(), fromRandom,
                Map.of("p0", "(bench_id(1 + (:i % 20000)))::text", "aid", "bench_id(:j)")));
        files.put("s05-sort-keyset", scenario("S5: the whole object sorted by an indexed field, page after a position",
                "\\set i random(1, :big_n)\n", null, List.of(new QuerySort("Email__c", false)),
                new After(List.of("x"), UUID.fromString("00000000-0000-0000-0000-000000000000")),
                Map.of("a0", "('user' || lpad((:i)::text, 9, '0') || '@example.test')", "aid", NIL)));
        files.put("s06a-and", scenario("S6: two indexed fields with AND",
                "\\set d random(0, 49)\n\\set s random(30000, 116000)\n\\set i random(1, :big_n)\n",
                QueryFilter.and(List.of(eq("Department__c", "x"), QueryFilter.on("Salary__c", Op.BETWEEN, 1, 2))),
                List.of(), fromRandom,
                Map.of("p0", department, "p1", "(:s)", "p2", "(:s + 2000)", "aid", "bench_id(:i)")));
        files.put("s06b-or", scenario("S6: two indexed fields with OR",
                "\\set d random(0, 49)\n\\set e random(0, 49)\n\\set i random(1, :big_n)\n",
                QueryFilter.or(List.of(eq("Department__c", "x"), eq("Department__c", "y"))), List.of(), fromRandom,
                Map.of("p0", department, "p1", "('Dept ' || lpad((:e)::text, 2, '0'))", "aid", "bench_id(:i)")));
        // The engine refuses these two above 20,000 records; the statement is measured as it would run, to size the
        // statement time limit of ADR-0108.
        files.put("s04a-nonindexed-common", scenario("S4 (a): a field without an index, common value",
                "\\set i random(1, :case_n)\n", eq("Stage__c", "x"), List.of(), null,
                Map.of("p0", "(array['New', 'Open', 'Won', 'Lost', 'Hold'])[1 + (:i % 5)]")));
        files.put("s04b-nonindexed-rare", scenario("S4 (b): a field without an index, one matching record",
                "\\set i random(1, :case_n)\n\\set seq :case_base + :i\n", eq("Code__c", "x"), List.of(), null,
                Map.of("p0", "('C' || lpad((:seq)::text, 9, '0'))")));
        files.replaceAll((name, text) -> name.startsWith("s04")
                ? text.replace("object_api_name = 'Employee__c'", "object_api_name = 'Case__c'") : text);
        return files;
    }

    /** The application's own update and insert, with the typed copies of the five indexed fields. */
    private static Map<String, String> writes() {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> update = new LinkedHashMap<>();
        update.put("tenant", "bench_tenant(1)");
        update.put("object", "'Employee__c'");
        update.put("id", "bench_id(:i)");
        update.put("version", ":v");
        update.put("actor", "bench_tenant(0)");
        update.put("patch", "(jsonb_build_object('Notes__c', 'updated ' || :v))::text");
        update.put("s_text_1", "'Dept ' || lpad((:i % 50)::text, 2, '0')");
        update.put("s_text_2", "'user' || lpad((:i)::text, 9, '0') || '@example.test'");
        update.put("s_num_1", "(30000 + ((:i)::bigint * 7919 % 90000))::text");
        update.put("s_date_1", "(date '2015-01-01' + (((:i)::bigint * 31) % 3650)::integer)::text");
        update.put("s_ref_1", "(bench_id(1 + (:i % 20000)))::text");
        files.put("s02p-update-production", "-- S2 with the statement of the application: all twenty-two typed copies "
                + "are written in the same statement (five hold a value). Compare with S2.\n"
                + "\\set i random(1, :big_n)\n" + SETTINGS + "select version as v from object_record where "
                + "tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and "
                + "deleted_at is null;\n\\gset\n"
                + fill(JsonbRecordStorage.updateStatement(), Map.of(), update) + ";\ncommit;\n");
        Map<String, String> insert = new LinkedHashMap<>();
        insert.put("tenant", "bench_tenant(1)");
        insert.put("object", "'Import__c'");
        insert.put("owner", "bench_tenant(0)");
        insert.put("actor", "bench_tenant(0)");
        insert.put("payload", "(bench_payload(:base))::text");
        insert.put("s_text_1", "(bench_row(:base)).s_text_1");
        insert.put("s_text_2", "(bench_row(:base)).s_text_2");
        insert.put("s_num_1", "((bench_row(:base)).s_num_1)::text");
        insert.put("s_date_1", "((bench_row(:base)).s_date_1)::text");
        insert.put("s_ref_1", "((bench_row(:base)).s_ref_1)::text");
        files.put("s09p-insert-production", "-- S9 with the statement of the application, one record per statement "
                + "(there is no bulk path before Sprint 17): the typed copies are written in the same statement.\n"
                + "\\set base random(1000000000, 2000000000)\n" + SETTINGS
                + fill(JsonbRecordStorage.insertStatement(), Map.of(), insert) + ";\ncommit;\n");
        return files;
    }

    /** Writes the scenario files into the folder. */
    public static void main(String[] args) throws IOException {
        Path folder = Path.of(args[0]);
        Files.createDirectories(folder);
        Map<String, String> all = new BenchmarkStatements().queries();
        all.putAll(writes());
        for (Map.Entry<String, String> file : all.entrySet()) {
            Files.writeString(folder.resolve(file.getKey() + ".sql"), file.getValue());
        }
    }
}
