package app.platform.integration.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.FileFormat;
import app.platform.integration.adapter.FileFormatException;
import app.platform.integration.adapter.FileFormatException.Problem;
import app.platform.integration.adapter.FileLayout;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.Transport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The file connector reads and writes the two formats by strict code of its own (Sprint 29A, ADR-0240): a file is read
 * as the connection says or refused as a whole, with a stable problem and never a word of what the file held; a row
 * is written back the way it was read; and the settings that belong together are judged together. No database, no
 * network.
 */
class FileTransferConnectorTest {

    private final FileTransferConnector connector = new FileTransferConnector();

    private static Map<String, String> settings(String format) {
        Map<String, String> settings = new HashMap<>();
        settings.put("hostKey", "SHA256:" + "A".repeat(43));
        settings.put("format", format);
        settings.put("outboxDirectory", "/outbox");
        settings.put("inboxDirectory", "/inbox");
        settings.put("processingDirectory", "/processing");
        settings.put("doneDirectory", "/done");
        settings.put("errorDirectory", "/error");
        settings.put("inboundEntity", "item");
        settings.put("idColumn", "id");
        settings.put("operationColumn", "operation");
        settings.put("pollMinutes", "5");
        settings.put("timeoutSeconds", "30");
        return settings;
    }

    private List<CanonicalEntity> csv(String text) {
        return connector.read(settings("csv"), text.getBytes(StandardCharsets.UTF_8), 100);
    }

    private List<CanonicalEntity> lines(String text) {
        return connector.read(settings("jsonl"), text.getBytes(StandardCharsets.UTF_8), 100);
    }

    private static Problem problemOf(Runnable read) {
        return org.junit.jupiter.api.Assertions.assertThrows(FileFormatException.class, read::run).problem();
    }

    // ---- what the connector is ----

    @Test
    void itIsAFileTransferConnectorWithItsOwnAddressSchemeAndTwoWaysToSignIn() {
        assertThat(connector.transport()).isEqualTo(Transport.SFTP);
        assertThat(Transport.SFTP.scheme()).isEqualTo("sftp");
        assertThat(connector.describe().key()).isEqualTo("file-transfer");
        assertThat(connector.describe().authKinds()).extracting(Enum::name).containsExactly("BASIC", "SSH_KEY");
        assertThat(connector.describe().settings()).extracting(setting -> setting.key()).contains("hostKey",
                "format", "inboxDirectory", "outboxDirectory", "inboundEntity", "pollMinutes");
        assertThat(connector.describe().settings().stream().filter(setting -> setting.key().equals("hostKey"))
                .findFirst().orElseThrow().required()).as("no trust on first use").isTrue();
        assertThat(connector.describe().toString() + connector.describe().label())
                .doesNotContainIgnoringCase("library");
    }

    @Test
    void theLayoutIsReadFromTheSettingsAndPicksUpOnlyWhenAnEntityIsNamed() {
        FileLayout layout = connector.layout(settings("jsonl"));

        assertThat(layout.format()).isEqualTo(FileFormat.JSONL);
        assertThat(layout.inbox()).isEqualTo("/inbox");
        assertThat(layout.pollEvery().toMinutes()).isEqualTo(5);
        assertThat(layout.operationTimeout().toSeconds()).isEqualTo(30);
        assertThat(layout.pulls()).isTrue();

        Map<String, String> none = settings("csv");
        none.remove("inboundEntity");
        assertThat(connector.layout(none).pulls()).isFalse();
    }

    @Test
    void settingsThatBelongTogetherAreJudgedTogether() {
        assertThat(connector.settingProblems(settings("csv"))).isEmpty();

        Map<String, String> clash = settings("csv");
        clash.put("doneDirectory", "/inbox");
        assertThat(connector.settingProblems(clash)).containsKey("settings.doneDirectory");

        Map<String, String> sameName = settings("csv");
        sameName.put("operationColumn", "id");
        assertThat(connector.settingProblems(sameName)).containsKey("settings.operationColumn");

        Map<String, String> badName = settings("csv");
        badName.put("idColumn", "1 bad;name");
        assertThat(connector.settingProblems(badName)).containsKey("settings.idColumn");
    }

    // ---- reading comma separated values ----

    @Test
    void aFileOfRowsIsReadIntoEntitiesWithTheIdentifierApartAndEmptyValuesLeftOut() {
        List<CanonicalEntity> rows = csv("id,title,note,operation\n1,First,,create\n2,\"Second, with a comma\","
                + "\"line one\nline two\",update\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).kind()).isEqualTo("item");
        assertThat(rows.get(0).externalId()).isEqualTo("1");
        assertThat(rows.get(0).fields()).containsOnlyKeys("title").containsEntry("title", "First");
        assertThat(rows.get(1).fields()).containsEntry("title", "Second, with a comma")
                .containsEntry("note", "line one\nline two").doesNotContainKey("operation");
    }

    @Test
    void aByteOrderMarkWindowsLineEndingsBlankLinesAndEscapedQuotesAreAllRead() {
        List<CanonicalEntity> rows = csv("﻿id,title\r\n\r\n1,\"say \"\"hi\"\"\"\r\n\r\n2,plain\r\n");

        assertThat(rows).extracting(CanonicalEntity::externalId).containsExactly("1", "2");
        assertThat(rows.get(0).fields()).containsEntry("title", "say \"hi\"");
    }

    @Test
    void aTrailingCommaIsAnEmptyLastValueAndTheIdentifierMayBeAnywhere() {
        List<CanonicalEntity> rows = csv("title,id,note\nFirst,7,\nSecond,8,x\n");

        assertThat(rows).extracting(CanonicalEntity::externalId).containsExactly("7", "8");
        assertThat(rows.get(0).fields()).containsOnlyKeys("title");
    }

    @Test
    void aValueThatLooksLikeAFormulaIsKeptAsText() {
        List<CanonicalEntity> rows = csv("id,title\n1,=1+1\n2,@SUM(A1)\n");

        assertThat(rows.get(0).fields()).containsEntry("title", "=1+1");
        assertThat(rows.get(1).fields()).containsEntry("title", "@SUM(A1)");
    }

    @Test
    void aFileThatIsNotReadableAsWhatTheConnectionSaysIsRefusedAsAWholeWithAStableProblem() {
        assertThat(problemOf(() -> csv(""))).isEqualTo(Problem.EMPTY);
        assertThat(problemOf(() -> csv("\n\n  \n".trim()))).isEqualTo(Problem.EMPTY);
        assertThat(problemOf(() -> csv("id,title\n"))).as("a header and no row").isEqualTo(Problem.EMPTY);
        assertThat(problemOf(() -> csv("title,note\nx,y\n"))).as("no identifier column")
                .isEqualTo(Problem.HEADER_INVALID);
        assertThat(problemOf(() -> csv("id,title,title\n1,a,b\n"))).as("a column twice")
                .isEqualTo(Problem.HEADER_INVALID);
        assertThat(problemOf(() -> csv("id,bad;name\n1,a\n"))).as("a column name that is not plain")
                .isEqualTo(Problem.HEADER_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1\n"))).as("a short row").isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1,a,b\n"))).as("a long row").isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n,a\n"))).as("no identifier").isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n" + "x".repeat(201) + ",a\n"))).as("an identifier too long")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1,\"never closed\n"))).as("an open quote")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1,a\"b\n"))).as("a quote in a plain value")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1,\"a\"b\n"))).as("text after a closing quote")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1," + "x".repeat(10_001) + "\n"))).as("a value too long")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> csv("id,title\n1,a\u0001b\n"))).as("a control character")
                .isEqualTo(Problem.NOT_TEXT);
        assertThat(problemOf(() -> connector.read(settings("csv"), new byte[] {(byte) 0xff, (byte) 0xfe, 'i', 'd'},
                10))).as("not UTF-8").isEqualTo(Problem.NOT_TEXT);
    }

    @Test
    void aFileWithMoreRowsThanTheBoundIsRefusedWithoutReadingToTheEnd() {
        StringBuilder text = new StringBuilder("id,title\n");
        for (int i = 0; i < 6; i++) {
            text.append(i).append(",a\n");
        }

        assertThat(problemOf(() -> connector.read(settings("csv"), text.toString().getBytes(StandardCharsets.UTF_8),
                5))).isEqualTo(Problem.TOO_MANY_ROWS);
        assertThat(connector.read(settings("csv"), text.toString().getBytes(StandardCharsets.UTF_8), 6)).hasSize(6);
    }

    @Test
    void aRefusalNeverHoldsAWordOfTheFile() {
        String secret = "secret-value-9f3a";
        for (String text : List.of("id,title\n1,\"" + secret, "title\n" + secret, "id," + secret + "\n1\n")) {
            FileFormatException refused = org.junit.jupiter.api.Assertions.assertThrows(FileFormatException.class,
                    () -> csv(text));

            assertThat(refused.getMessage() + refused + refused.problem()).doesNotContain(secret);
            assertThat(refused.getCause()).isNull();
            assertThat(refused.getStackTrace()).isEmpty();
        }
    }

    // ---- reading one object per line ----

    @Test
    void aFileOfFlatObjectsIsReadLikeATableAndNullIsAValueThatIsNotThere() {
        List<CanonicalEntity> rows = lines("{\"id\":\"1\",\"title\":\"First\",\"n\":3,\"ok\":true,\"gone\":null}\n\n"
                + "{\"id\":2,\"title\":\"Second\",\"operation\":\"update\"}\r\n");

        assertThat(rows).extracting(CanonicalEntity::externalId).containsExactly("1", "2");
        assertThat(rows.get(0).fields()).containsEntry("title", "First").containsEntry("ok", true)
                .containsEntry("n", new BigDecimal("3")).doesNotContainKey("gone");
        assertThat(rows.get(1).fields()).containsOnlyKeys("title");
    }

    @Test
    void aLineThatIsNotAFlatObjectWithAnIdentifierRefusesTheWholeFile() {
        assertThat(problemOf(() -> lines(""))).isEqualTo(Problem.EMPTY);
        assertThat(problemOf(() -> lines("[1,2]\n"))).as("a list").isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("not json\n"))).isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("{\"id\":\"1\",\"n\":{\"a\":1}}\n"))).as("nested")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("{\"id\":\"1\",\"n\":[1]}\n"))).as("a list inside")
                .isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("{\"title\":\"x\"}\n"))).as("no identifier").isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("{\"id\":\"\"}\n"))).isEqualTo(Problem.ROW_INVALID);
        assertThat(problemOf(() -> lines("{\"id\":\"1\",\"bad;key\":\"x\"}\n"))).as("a key that is not plain")
                .isEqualTo(Problem.ROW_INVALID);
    }

    // ---- writing ----

    private OutboundRequest send(String format, Operation operation, CanonicalEntity entity) {
        return connector.send(settings(format), operation, entity);
    }

    private static CanonicalEntity entity(String id, Map<String, Object> fields) {
        return new CanonicalEntity("item", id, fields);
    }

    @Test
    void aRowIsWrittenWithItsIdentifierAndWhatHappenedThenItsValuesInTheOrderTheyCame() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "A, \"quoted\" title");
        fields.put("amount", new BigDecimal("12.50"));
        fields.put("active", true);
        fields.put("day", LocalDate.of(2026, 10, 31));
        fields.put("seen", Instant.parse("2026-10-31T10:00:00Z"));

        OutboundRequest request = send("csv", Operation.CREATE, entity("rec-1", fields));

        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("id,operation,title,amount,active,"
                + "day,seen\nrec-1,create,\"A, \"\"quoted\"\" title\",12.50,true,2026-10-31,2026-10-31T10:00:00Z\n");
        assertThat(request.path()).isEqualTo("/file.csv");
        assertThat(request.contentType()).isEqualTo("text/csv");
        assertThat(request.method()).isEqualTo(OutboundRequest.Method.PUT);
        assertThat(new String(send("csv", Operation.UPDATE, entity("rec-1", Map.of())).body(),
                StandardCharsets.UTF_8)).isEqualTo("id,operation\nrec-1,update\n");
        assertThat(new String(send("csv", Operation.DELETE, entity("rec-1", Map.of())).body(),
                StandardCharsets.UTF_8)).isEqualTo("id,operation\nrec-1,delete\n");
    }

    @Test
    void aValueThatLooksLikeAFormulaIsWrittenAsItIsBecauseTheFileIsForASystemAndNotForAPerson() {
        OutboundRequest request = send("csv", Operation.CREATE, entity("1", Map.of("title", "=1+1")));

        assertThat(new String(request.body(), StandardCharsets.UTF_8)).endsWith("1,create,=1+1\n");
    }

    @Test
    void whatIsWrittenIsReadBackAsTheSameEntityInBothFormats() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "Line one\nline \"two\", with a comma");
        fields.put("note", "  padded  ");
        fields.put("count", new BigDecimal("42"));

        for (String format : List.of("csv", "jsonl")) {
            byte[] written = send(format, Operation.UPDATE, entity("rec-9", fields)).body();

            List<CanonicalEntity> back = connector.read(settings(format), written, 10);

            assertThat(back).as(format).hasSize(1);
            assertThat(back.getFirst().externalId()).isEqualTo("rec-9");
            assertThat(back.getFirst().fields().get("title")).as(format).isEqualTo(fields.get("title"));
            assertThat(back.getFirst().fields().get("note")).as(format).isEqualTo("  padded  ");
            assertThat(back.getFirst().fields().get("count").toString()).as(format).isEqualTo("42");
        }
    }

    @Test
    void aJsonLinesRowIsOneLineWithTheValuesTyped() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "x");
        fields.put("amount", new BigDecimal("1.5"));
        fields.put("active", false);

        String line = new String(send("jsonl", Operation.CREATE, entity("1", fields)).body(),
                StandardCharsets.UTF_8);

        assertThat(line).isEqualTo("{\"id\":\"1\",\"operation\":\"create\",\"title\":\"x\",\"amount\":1.5,"
                + "\"active\":false}\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
        assertThat(send("jsonl", Operation.CREATE, entity("1", Map.of())).path()).isEqualTo("/file.jsonl");
    }

    @Test
    void whatCannotBeWrittenAsAColumnIsRefusedNotChanged() {
        for (String name : List.of("id", "operation", "has space;", "", "1starts-with-a-digit")) {
            assertThatThrownBy(() -> send("csv", Operation.CREATE, entity("1", Map.of(name, "x"))))
                    .as(name).isInstanceOf(PayloadException.class);
            assertThatThrownBy(() -> send("jsonl", Operation.CREATE, entity("1", Map.of(name, "x"))))
                    .as(name).isInstanceOf(PayloadException.class);
        }
        List<String> badValues = new ArrayList<>(List.of("a\u0000b", "a\u0001b", "a\u007fb"));
        for (String value : badValues) {
            assertThatThrownBy(() -> send("csv", Operation.CREATE, entity("1", Map.of("title", value))))
                    .isInstanceOf(PayloadException.class);
        }
    }

    // ---- the answer after a write ----

    @Test
    void theSmallAnswerAfterAWriteNamesTheEntity() {
        CanonicalEntity named = connector.receive(settings("csv"), "item", "{\"id\":\"rec-5\"}".getBytes(
                StandardCharsets.UTF_8));

        assertThat(named.externalId()).isEqualTo("rec-5");
        assertThatThrownBy(() -> connector.receive(settings("csv"), "item", "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadException.class);
        assertThatThrownBy(() -> connector.receive(settings("csv"), "item", "nope".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadException.class);
        assertThat(connector.accepted(settings("csv"), Operation.CREATE, 201)).isTrue();
        assertThat(connector.accepted(settings("csv"), Operation.CREATE, 500)).isFalse();
    }
}
