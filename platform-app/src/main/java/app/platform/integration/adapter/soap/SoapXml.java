package app.platform.integration.adapter.soap;

import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.PayloadException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Writes and reads the SOAP 1.1 envelopes of the SOAP connector (Sprint 29, ADR-0235), pure and with nothing but the
 * JDK's parser. It is the only place XML from an outside system is read, so it is the place that makes that safe:
 *
 * <ul>
 *   <li>a document type declaration is refused outright, so there is no entity to expand, no external entity to fetch
 *       and no way to make the parser open a connection of its own;
 *   <li>secure processing is on, external access is switched off for every protocol, XInclude is off;
 *   <li>the size of the body is bounded by the caller before it gets here, and the number of elements and their depth
 *       are bounded here, so a small document cannot grow a large tree;
 *   <li>only text, numbers and yes and no are read, and only from elements that hold nothing else; anything nested is
 *       ignored (the platform's fields are flat).
 * </ul>
 *
 * <p>A refusal is a {@link PayloadException} whose words never repeat what the document said.
 */
final class SoapXml {

    static final String ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final int MAX_ELEMENTS = 2_000;
    private static final int MAX_DEPTH = 16;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}");

    private SoapXml() {
    }

    /** Whether the text is a name the envelope may carry as an element: a letter or underscore, then simple marks. */
    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.regionMatches(true, 0, "xml", 0, 3);
    }

    /** The envelope for one operation: the operation's element holds the identifier and the fields. */
    static byte[] write(String namespace, String operation, String idElement, CanonicalEntity entity) {
        if (!validName(operation) || !validName(idElement)) {
            throw new PayloadException("The operation or the identifier cannot be written as an element.");
        }
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"").append(ENVELOPE_NS).append("\"><soap:Body>");
        xml.append("<ns:").append(operation).append(" xmlns:ns=\"").append(escape(namespace)).append("\">");
        if (entity.externalId() != null) {
            element(xml, idElement, entity.externalId());
        }
        for (Map.Entry<String, Object> field : entity.fields().entrySet()) {
            if (!validName(field.getKey())) {
                throw new PayloadException("A field cannot be written as an element.");
            }
            element(xml, field.getKey(), text(field.getValue()));
        }
        xml.append("</ns:").append(operation).append("></soap:Body></soap:Envelope>");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void element(StringBuilder xml, String name, String value) {
        xml.append("<ns:").append(name).append('>').append(escape(value)).append("</ns:").append(name).append('>');
    }

    private static String text(Object value) {
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        return value.toString();
    }

    /** Text safe inside an element or an attribute: the five marks, and no character XML cannot hold. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    boolean allowed = c == 0x9 || c == 0xA || c == 0xD || c >= 0x20 && c <= 0xD7FF
                            || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF;
                    if (allowed) {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    /**
     * Reads the response element of an envelope as an entity: the element named {@code idElement} is the identifier,
     * every other leaf element is a field.
     *
     * @throws PayloadException when the document is not an envelope, is a fault or breaks a bound
     */
    static CanonicalEntity read(String kind, String idElement, byte[] body) {
        Document document = parse(body);
        Element root = document.getDocumentElement();
        if (root == null || !"Envelope".equals(root.getLocalName()) || !ENVELOPE_NS.equals(root.getNamespaceURI())) {
            throw new PayloadException("The payload is not a SOAP envelope.");
        }
        Element soapBody = firstChild(root, "Body");
        if (soapBody == null) {
            throw new PayloadException("The envelope has no body.");
        }
        Element response = firstElement(soapBody);
        if (response == null) {
            throw new PayloadException("The envelope body is empty.");
        }
        if ("Fault".equals(response.getLocalName())) {
            throw new PayloadException("The service answered with a fault.");
        }
        String id = null;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Node child = response.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element) || !leaf(element)) {
                continue;
            }
            String name = element.getLocalName();
            String value = element.getTextContent();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (name.equals(idElement)) {
                id = value.strip();
            } else if (validName(name)) {
                fields.put(name, scalar(value.strip()));
            }
        }
        return new CanonicalEntity(kind, id, fields);
    }

    /** A number or a yes and no when the text is plainly one, else the text. */
    private static Object scalar(String text) {
        if (text.equals("true") || text.equals("false")) {
            return Boolean.valueOf(text);
        }
        if (text.length() <= 38 && text.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return new BigDecimal(text);
        }
        return text;
    }

    private static boolean leaf(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return false;
            }
        }
        return true;
    }

    private static Element firstChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static Element firstElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private static Document parse(byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // A parser must not report where it failed with text from the document: errors are dropped.
            builder.setErrorHandler(null);
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are refused.");
            });
            Document document = builder.parse(new InputSource(new ByteArrayInputStream(body)));
            bound(document.getDocumentElement(), 1, new int[1]);
            return document;
        } catch (ParserConfigurationException | SAXException | IOException unreadable) {
            throw new PayloadException("The payload is not an XML document this connector reads.");
        }
    }

    private static void bound(Node node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_ELEMENTS) {
            throw new PayloadException("The payload is deeper or larger than this connector reads.");
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                bound(child, depth + 1, count);
            }
        }
    }
}
