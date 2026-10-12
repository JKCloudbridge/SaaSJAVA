package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The exit criterion of Sprint 15 (ADR-0092): field security is enforced on both read and write for every field type.
 * One object has a field of each of the 19 types; three members hold the same object permissions and differ only in
 * their field permissions: one may edit every field, one may only read them, one has no field permission at all. For
 * each type the tests prove, through the real API:
 *
 * <ul>
 * <li>the member who may edit writes the field and reads it back;</li>
 * <li>the member who may only read is refused, whole, on every path to the field (create, update, clear, null), and
 * still reads it;</li>
 * <li>the member with no permission is told what they would be told about a field that is not there, never sees the
 * field in a single read, a list or the recycle bin, and is not told its name by any error.</li>
 * </ul>
 */
@PlatformIntegrationTest
class FieldSecurityMatrixIT extends PipelineSupport {

    private static final String NOT_EDITABLE = "You may not change this field.";
    private static final String NOT_A_FIELD = "This is not a field of the object.";

    private static List<Case> writable() {
        return CASES.stream().filter(one -> !one.calculated()).toList();
    }

    /** True when the JSON read back is the value that was sent (numbers compare by value, not by spelling). */
    private static boolean sameValue(Object actual, String sentJson) {
        if (sentJson.startsWith("\"")) {
            return sentJson.substring(1, sentJson.length() - 1).replace("\\n", "\n").equals(actual);
        }
        if (sentJson.startsWith("[")) {
            List<String> parts = new ArrayList<>();
            for (String part : sentJson.substring(1, sentJson.length() - 1).split(",")) {
                parts.add(part.replace("\"", ""));
            }
            return parts.equals(actual);
        }
        if (sentJson.equals("true") || sentJson.equals("false")) {
            return Boolean.parseBoolean(sentJson) == (actual instanceof Boolean flag && flag);
        }
        return actual instanceof Number number
                && new BigDecimal(number.toString()).compareTo(new BigDecimal(sentJson)) == 0;
    }

    @Test
    void aRecordThatPassesThePipelineReadsBackAsItWasWrittenWithTheFormulaAndTheNumberCalculated() {
        Matrix matrix = matrix();

        Response created = createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId()));
        Response read = matrix.admin().get(MATRIX_RECORDS + "/" + idOf(created));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Map<String, Object> fields = fieldsOf(read);
        for (Case one : writable()) {
            assertThat(sameValue(fields.get(one.api()), valid(one, matrix.targetId())))
                    .as(one.type() + " reads back as written: " + fields.get(one.api())).isTrue();
        }
        assertThat(new BigDecimal(fields.get("calc__c").toString())).isEqualByComparingTo("24690");
        assertThat(fields.get("code__c")).isEqualTo("M-0001");
        assertThat(created.body()).as("a record without a type has no recordType key").doesNotContain("recordType");
    }

    @Test
    void everyTypeRefusesAValueThatDoesNotBelongAndNamesTheField() {
        Matrix matrix = matrix();

        for (Case one : writable()) {
            Response refused = createIn(matrix.admin(), MATRIX_RECORDS, "{\"parent__c\":\"" + matrix.targetId()
                    + "\",\"" + one.api() + "\":" + one.invalid() + "}");

            assertThat(refused.status()).as(one.type() + ": " + refused.body()).isEqualTo(400);
            assertThat(problemAt(refused, one.api())).as(one.type()).isNotNull();
            assertThat(placesOf(refused)).as(one.type() + " is the only place").containsExactly(one.api());
            if (one.invalid().length() > 8) {
                assertThat(refused.body()).as("a message never repeats the value")
                        .doesNotContain(one.invalid().replace("\"", ""));
            }
        }
    }

    @Test
    void nobodyWritesAFormulaOrAnAutoNumber() {
        Matrix matrix = matrix();

        for (Case one : CASES.stream().filter(Case::calculated).toList()) {
            Response refused = createIn(matrix.admin(), MATRIX_RECORDS,
                    "{\"parent__c\":\"" + matrix.targetId() + "\",\"" + one.api() + "\":" + one.valid() + "}");

            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(problemAt(refused, one.api())).isEqualTo("This field cannot be written.");
        }
    }

    @Test
    void aMemberWhoMayEditEveryFieldWritesEachTypeAndReadsItBack() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, "{\"parent__c\":\"" + matrix.targetId() + "\"}"));
        TestBrowser editor = as(matrix.organization(), matrixMember(matrix, "edit").member());

        for (Case one : writable()) {
            Response changed = patchIn(editor, MATRIX_RECORDS, id, currentVersion(editor, MATRIX_RECORDS, id),
                    "{\"" + one.api() + "\":" + valid(one, matrix.targetId()) + "}");

            assertThat(changed.status()).as(one.type() + ": " + changed.body()).isEqualTo(200);
            assertThat(sameValue(fieldsOf(editor.get(MATRIX_RECORDS + "/" + id)).get(one.api()),
                    valid(one, matrix.targetId()))).as(one.type()).isTrue();
        }
        Response created = createIn(editor, MATRIX_RECORDS, everything(matrix.targetId()));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    @Test
    void aMemberWhoMayOnlyReadAFieldIsRefusedOnEveryPathAndStillReadsIt() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        TestBrowser reader = as(matrix.organization(), matrixMember(matrix, "read").member());

        for (Case one : CASES) {
            long version = currentVersion(reader, MATRIX_RECORDS, id);
            String value = valid(one, matrix.targetId());
            List<Response> refusals = List.of(
                    patchIn(reader, MATRIX_RECORDS, id, version, "{\"" + one.api() + "\":" + value + "}"),
                    reader.request("PATCH", MATRIX_RECORDS + "/" + id,
                            "{\"version\":" + version + ",\"clear\":[\"" + one.api() + "\"]}"),
                    patchIn(reader, MATRIX_RECORDS, id, version, "{\"" + one.api() + "\":null}"),
                    createIn(reader, MATRIX_RECORDS, "{\"" + one.api() + "\":" + value + "}"));

            for (Response refused : refusals) {
                assertThat(refused.status()).as(one.type() + ": " + refused.body()).isEqualTo(400);
                assertThat(problemAt(refused, one.api())).as(one.type()).isEqualTo(NOT_EDITABLE);
            }
            assertThat(currentVersion(reader, MATRIX_RECORDS, id)).as("nothing was saved").isEqualTo(version);
            assertThat(fieldsOf(reader.get(MATRIX_RECORDS + "/" + id))).as(one.type() + " is still read")
                    .containsKey(one.api());
        }
    }

    @Test
    void aWriteWithOneForbiddenFieldSavesNothingNotEvenTheAllowedFields() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        String fields = "[{\"key\":\"Matrix__c.txt__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"Matrix__c.num__c\",\"actions\":[\"read\"]}]";
        TestBrowser member = as(matrix.organization(),
                holderWith(matrix.organization(), matrix.admin(), "Matrix__c", fields, "read", "update").member());

        Response refused = patchIn(member, MATRIX_RECORDS, id, 0, "{\"txt__c\":\"changed\",\"num__c\":5}");
        Response unreadable = patchIn(member, MATRIX_RECORDS, id, 0, "{\"txt__c\":\"changed\",\"dec__c\":5}");
        Response allowed = patchIn(member, MATRIX_RECORDS, id, 0, "{\"txt__c\":\"changed\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(placesOf(refused)).containsExactly("num__c");
        assertThat(problemAt(refused, "num__c")).isEqualTo(NOT_EDITABLE);
        assertThat(unreadable.status()).isEqualTo(400);
        assertThat(problemAt(unreadable, "dec__c")).as("a field the member cannot read: as if it were not there")
                .isEqualTo(NOT_A_FIELD);
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(fieldsOf(member.get(MATRIX_RECORDS + "/" + id))).containsOnlyKeys("txt__c", "num__c")
                .containsEntry("txt__c", "changed");
    }

    @Test
    void aMemberWithNoFieldPermissionIsToldWhatTheyWouldBeToldOfAFieldThatIsNotThere() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        TestBrowser blind = as(matrix.organization(), matrixMember(matrix, null).member());

        Response unknown = patchIn(blind, MATRIX_RECORDS, id, 0, "{\"nope__c\":1}");
        assertThat(problemAt(unknown, "nope__c")).isEqualTo(NOT_A_FIELD);
        for (Case one : CASES) {
            long version = currentVersion(blind, MATRIX_RECORDS, id);
            List<Response> refusals = List.of(
                    patchIn(blind, MATRIX_RECORDS, id, version, "{\"" + one.api() + "\":" + valid(one,
                            matrix.targetId()) + "}"),
                    blind.request("PATCH", MATRIX_RECORDS + "/" + id,
                            "{\"version\":" + version + ",\"clear\":[\"" + one.api() + "\"]}"),
                    createIn(blind, MATRIX_RECORDS, "{\"" + one.api() + "\":1}"));

            for (Response refused : refusals) {
                assertThat(refused.status()).as(one.type() + ": " + refused.body()).isEqualTo(400);
                assertThat(problemAt(refused, one.api())).as(one.type()).isEqualTo(NOT_A_FIELD);
            }
        }
    }

    @Test
    void aMemberWhoMayNotReadFieldsNeverSeesThemInASingleReadAListOrTheRecycleBin() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        String second = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        matrix.admin().request("DELETE", MATRIX_RECORDS + "/" + second, null);
        TestBrowser blind = as(matrix.organization(), matrixMember(matrix, null).member());

        Response single = blind.get(MATRIX_RECORDS + "/" + id);
        Response list = blind.get(MATRIX_RECORDS);
        Response bin = blind.get(MATRIX_BIN);

        assertThat(single.status()).isEqualTo(200);
        assertThat(fieldsOf(single)).as("records with no values").isEmpty();
        assertThat(JsonPath.<List<Map<String, Object>>>read(list.body(), "$.data[*].fields")).isNotEmpty()
                .allSatisfy(fields -> assertThat(fields).isEmpty());
        assertThat(JsonPath.<List<Map<String, Object>>>read(bin.body(), "$.data[*].fields")).hasSize(1)
                .allSatisfy(fields -> assertThat(fields).isEmpty());
        assertThat(single.body() + list.body() + bin.body()).doesNotContain("M-0001").doesNotContain("abc")
                .doesNotContain("user-a@example.test");
    }

    @Test
    void aMemberSeesExactlyTheFieldsTheyMayReadAndTheyAreTheUnionOfWhatTheyHold() {
        Matrix matrix = matrix();
        String id = idOf(createIn(matrix.admin(), MATRIX_RECORDS, everything(matrix.targetId())));
        String fields = "[{\"key\":\"Matrix__c.txt__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"Matrix__c.calc__c\",\"actions\":[\"read\"]},"
                + "{\"key\":\"Matrix__c.code__c\",\"actions\":[\"read\"]}]";
        TestBrowser member = as(matrix.organization(),
                holderWith(matrix.organization(), matrix.admin(), "Matrix__c", fields, "read").member());

        assertThat(fieldsOf(member.get(MATRIX_RECORDS + "/" + id))).containsOnlyKeys("txt__c", "calc__c", "code__c");
    }

    @Test
    void aRequiredFieldTheMemberCannotReadIsNotNamedWhenACreateLacksIt() {
        Matrix matrix = matrix();
        String fields = "[{\"key\":\"Matrix__c.txt__c\",\"actions\":[\"edit\"]}]";
        TestBrowser member = as(matrix.organization(),
                holderWith(matrix.organization(), matrix.admin(), "Matrix__c", fields, "read", "create").member());

        Response refused = createIn(member, MATRIX_RECORDS, "{\"txt__c\":\"a\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(placesOf(refused)).containsExactly("fields");
        assertThat(refused.body()).doesNotContain("parent__c");
    }
}
