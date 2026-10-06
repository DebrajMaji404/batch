package com.eazy.batch.reader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streams the records of an XML upload: a root element whose child elements are the records,
 * each holding one child element (or attribute) per field:
 *
 * <pre>
 * &lt;rows&gt;
 *   &lt;row&gt;&lt;name&gt;Asha&lt;/name&gt;&lt;age&gt;21&lt;/age&gt;&lt;/row&gt;
 * &lt;/rows&gt;
 * </pre>
 *
 * The names of the root and record elements do not matter. DTDs and external entities are
 * switched off, so an uploaded file cannot read local files or reach the network.
 */
final class XmlRecordSource implements RecordSource {

    private final XMLStreamReader xml;
    private boolean started;
    private boolean finished;

    XmlRecordSource(InputStream in) throws IOException {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.IS_COALESCING, true);
            this.xml = factory.createXMLStreamReader(in);
        } catch (XMLStreamException e) {
            throw new IOException("Invalid XML: " + e.getMessage(), e);
        }
    }

    @Override
    public Record next() throws IOException {
        if (finished) return null;
        try {
            if (!started) {
                started = true;
                while (xml.hasNext() && xml.getEventType() != XMLStreamConstants.START_ELEMENT) {
                    xml.next();
                }
                if (xml.getEventType() != XMLStreamConstants.START_ELEMENT) {
                    throw new IOException("Invalid XML: no root element");
                }
            }
            // between records: look for the next child of the root
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) return readRecord();
                if (event == XMLStreamConstants.END_ELEMENT) break; // end of the root
            }
            finished = true;
            return null;
        } catch (XMLStreamException e) {
            finished = true;
            throw new IOException("Invalid XML: " + e.getMessage(), e);
        }
    }

    /** Positioned on the record's start element; leaves the reader on its end element. */
    private Record readRecord() throws XMLStreamException {
        Map<String, String> values = new LinkedHashMap<>();
        String error = null;

        for (int i = 0; i < xml.getAttributeCount(); i++) {
            values.put(xml.getAttributeLocalName(i), xml.getAttributeValue(i));
        }

        int depth = 1;
        String field = null;
        StringBuilder text = new StringBuilder();
        boolean nestedInField = false;

        while (depth > 0 && xml.hasNext()) {
            switch (xml.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    if (depth == 2) {
                        field = xml.getLocalName();
                        text.setLength(0);
                        nestedInField = false;
                        if (xml.getAttributeCount() > 0 && error == null) {
                            error = "element <" + field + "> has attributes; only flat records are supported";
                        }
                    } else {
                        nestedInField = true;
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (depth == 2) text.append(xml.getText());
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == 2) {
                        if (nestedInField) {
                            if (error == null) {
                                error = "element <" + field + "> contains other elements; only flat records are supported";
                            }
                        } else {
                            if (values.containsKey(field) && error == null) {
                                error = "field <" + field + "> appears more than once";
                            }
                            values.put(field, text.toString());
                        }
                    }
                    depth--;
                }
                default -> {
                    // comments, processing instructions: ignored
                }
            }
        }
        return new Record(values, error);
    }

    @Override
    public void close() throws IOException {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }
}
