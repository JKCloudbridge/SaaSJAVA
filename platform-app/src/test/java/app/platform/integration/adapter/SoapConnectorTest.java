package app.platform.integration.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.adapter.soap.SoapConnector;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SOAP connector without a network (Sprint 29, ADR-0235): the envelope it writes, the answer it reads and the
 * documents it refuses. XML from an outside system is the riskiest text the platform reads, so the hostile ones are a
 * corpus: a document type declaration, an external entity, an entity bomb, a document that is too deep or too large,
 * and a document that is not an envelope.
 */
class SoapConnectorTest {

    private static final String NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private final SoapConnector connector = new SoapConnector();
    private final Map<String, String> settings = Map.of("namespace", "urn:erp.example", "servicePath", "/service",
            "actionPrefix", "urn:erp.example/", "idElement", "Id");

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String envelope(String body) {
        return "<?xml version=\"1.0\"?><soap:Envelope xmlns:soap=\"" + NS + "\"><soap:Body>" + body
                + "</soap:Body></soap:Envelope>";
    }

    @Test
    void aCreateIsAnEnvelopeNamedByTheRuleWithTheActionInAHeader() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("Title", "A <b>&\"'</b> title");
        fields.put("Amount", new BigDecimal("12.50"));
        fields.put("Active", true);

        OutboundRequest request = connector.send(settings, Operation.CREATE, new CanonicalEntity("purchase-order",
                null, fields));

        String xml = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(request.method()).isEqualTo(OutboundRequest.Method.POST);
        assertThat(request.path()).isEqualTo("/service");
        assertThat(request.contentType()).isEqualTo("text/xml; charset=utf-8");
        assertThat(request.headers()).containsEntry("SOAPAction", "\"urn:erp.example/CreatePurchaseOrder\"");
        assertThat(xml).contains("<ns:CreatePurchaseOrder xmlns:ns=\"urn:erp.example\">")
                .contains("<ns:Title>A &lt;b&gt;&amp;&quot;&apos;&lt;/b&gt; title</ns:Title>")
                .contains("<ns:Amount>12.50</ns:Amount>").contains("<ns:Active>true</ns:Active>")
                .doesNotContain("<ns:Id>");
    }

    @Test
    void anUpdateCarriesTheIdentifierAndARemovalOnlyIt() {
        CanonicalEntity entity = new CanonicalEntity("item", "ext-7", Map.of("Title", "x"));

        String update = new String(connector.send(settings, Operation.UPDATE, entity).body(), StandardCharsets.UTF_8);
        String removal = new String(connector.send(settings, Operation.DELETE, entity).body(),
                StandardCharsets.UTF_8);

        assertThat(update).contains("<ns:UpdateItem").contains("<ns:Id>ext-7</ns:Id>").contains("<ns:Title>x");
        assertThat(removal).contains("<ns:DeleteItem").contains("<ns:Id>ext-7</ns:Id>").doesNotContain("Title");
        CanonicalEntity withoutId = new CanonicalEntity("item", null, Map.of());
        assertThatThrownBy(() -> connector.send(settings, Operation.UPDATE, withoutId))
                .isInstanceOf(PayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1bad", "has space", "a<b", "xmlfoo", "a:b", "", "a/b"})
    void aFieldThatCannotBeAnElementIsRefusedAndNeverWrittenLoosely(String name) {
        CanonicalEntity entity = new CanonicalEntity("item", null, Map.of(name, "x"));

        assertThatThrownBy(() -> connector.send(settings, Operation.CREATE, entity))
                .isInstanceOf(PayloadException.class);
    }

    @Test
    void aTextWithACharacterXmlCannotHoldHasItLeftOut() {
        CanonicalEntity entity = new CanonicalEntity("item", null, Map.of("Title", "a\u0000b\u0008c"));

        String xml = new String(connector.send(settings, Operation.CREATE, entity).body(), StandardCharsets.UTF_8);

        assertThat(xml).contains("<ns:Title>abc</ns:Title>");
    }

    @Test
    void anAnswerIsReadIntoAnEntityOfLeafElementsOnly() {
        byte[] answer = bytes(envelope("<r:CreateItemResponse xmlns:r=\"urn:erp.example\"><r:Id> ext-9 </r:Id>"
                + "<r:Title>Hello</r:Title><r:Amount>3.25</r:Amount><r:Done>true</r:Done><r:Nested><r:X>1</r:X>"
                + "</r:Nested></r:CreateItemResponse>"));

        CanonicalEntity entity = connector.receive(settings, "item", answer);

        assertThat(entity.externalId()).isEqualTo("ext-9");
        assertThat(entity.fields()).containsEntry("Title", "Hello").containsEntry("Amount", new BigDecimal("3.25"))
                .containsEntry("Done", true).doesNotContainKey("Nested").doesNotContainKey("X");
    }

    @Test
    void aFaultIsNotAnEntity() {
        byte[] fault = bytes(envelope("<soap:Fault><faultcode>soap:Server</faultcode><faultstring>secret detail"
                + "</faultstring></soap:Fault>"));

        assertThatThrownBy(() -> connector.receive(settings, "item", fault)).isInstanceOf(PayloadException.class)
                .hasMessageNotContaining("secret detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE x [<!ENTITY a \"b\">]><x>&a;</x>",
            "<!DOCTYPE x SYSTEM \"http://127.0.0.1:1/evil.dtd\"><x/>",
            "<!DOCTYPE x [<!ENTITY a SYSTEM \"file:///etc/passwd\">]><x>&a;</x>",
            "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                    + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]><lolz>&lol3;</lolz>",
            "not xml at all",
            "<a><b></a>",
            "",
            "<other/>"})
    void aHostileOrBrokenDocumentIsRefusedWithoutRepeatingIt(String document) {
        assertThatThrownBy(() -> connector.receive(settings, "item", bytes(document)))
                .isInstanceOf(PayloadException.class).hasMessageNotContaining("passwd")
                .hasMessageNotContaining("evil");
    }

    @Test
    void aDocumentThatIsTooDeepOrTooLargeIsRefused() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("<d>");
        }
        deep.append("x");
        for (int i = 0; i < 40; i++) {
            deep.append("</d>");
        }
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 2_500; i++) {
            wide.append("<f").append(i % 9).append(">v</f").append(i % 9).append(">");
        }

        assertThatThrownBy(() -> connector.receive(settings, "item", bytes(envelope("<r>" + deep + "</r>"))))
                .isInstanceOf(PayloadException.class);
        assertThatThrownBy(() -> connector.receive(settings, "item", bytes(envelope("<r>" + wide + "</r>"))))
                .isInstanceOf(PayloadException.class);
    }

    @Test
    void theDescriptionIsAClosedFormTheScreensDrawAndTheSettingsAreChecked() {
        ConnectorDescription description = connector.describe();

        assertThat(description.key()).isEqualTo("soap");
        assertThat(description.settings()).extracting(SettingSpec::key).contains("namespace", "servicePath",
                "actionPrefix", "idElement", "healthPath", "expect", "timeoutSeconds", "idempotencyHeader");
        assertThat(description.platformEvents()).extracting(ConnectorDescription.EventSpec::key)
                .containsExactly("record.created", "record.updated", "record.deleted");
        assertThat(connector.idempotencyHeader(Map.of("idempotencyHeader", "X-Once"))).isEqualTo("X-Once");
        assertThat(connector.idempotencyHeader(Map.of())).isNull();
        assertThat(connector.accepted(settings, Operation.CREATE, 200)).isTrue();
        assertThat(connector.accepted(settings, Operation.CREATE, 500)).isFalse();
    }
}
