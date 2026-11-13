package app.platform.sheets.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The one way the upload reads XML from a file (SH1B, ADR-0195, ADR-0253): a stream reader that resolves nothing, with
 * the document type declaration refused outright, plus the small number and attribute helpers every reader of a part
 * needs. A part that declares a document type is refused before it is parsed, because a workbook never has one and a
 * declaration is the way entities are defined.
 */
final class Xml {

    private Xml() {
    }

    /**
     * Refuses a part that declares a document type or an entity. Looked for in the first 4,096 bytes, where a
     * declaration must stand, in any case.
     */
    static void refuseDoctype(byte[] part) {
        String head = new String(part, 0, Math.min(part.length, 4_096), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        if (head.contains("<!doctype") || head.contains("<!entity")) {
            throw new ImportRefusal("doctype", "The file has a part with a document type declaration, which a "
                    + "workbook never has.");
        }
    }

    /** A stream reader over the bytes of a part that resolves nothing: no document type, no entity, no schema. */
    static XMLStreamReader reader(byte[] part) {
        refuseDoctype(part);
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try {
            // Belt and braces: the document type is already refused, so nothing external is ever asked for.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException e) {
            // A parser that does not know the property resolves nothing either, because of the switches above.
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        }
        try {
            return factory.createXMLStreamReader(new ByteArrayInputStream(part));
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        }
    }

    static void close(XMLStreamReader r) {
        try {
            r.close();
        } catch (XMLStreamException e) {
            // The reader holds a byte array and no file: there is nothing to release.
            return;
        }
    }

    /** The value of the attribute with this local name (in any namespace), or null. */
    static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (local.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    /** The relationship identifier of an element ({@code r:id}), or null. */
    static String relationshipId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("id".equals(r.getAttributeLocalName(i)) && r.getAttributeNamespace(i) != null
                    && !r.getAttributeNamespace(i).isEmpty()) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    static Integer integer(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long longer(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double number(String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean flag(String text, boolean absent) {
        if (text == null) {
            return absent;
        }
        return !("0".equals(text) || "false".equalsIgnoreCase(text));
    }

    static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(most, value));
    }
}
