package com.gaterook.connectors.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * S3's XML answers ({@code ListAllMyBucketsResult}, {@code ListBucketResult}, {@code CopyObjectResult},
 * {@code Error}), read by local element names so that every S3-compatible service's namespace, or none, works. The
 * parser refuses a DOCTYPE outright and never resolves an external entity, DTD, schema or XInclude: an answer is
 * data from a server, never a reason to open another file or connection.
 */
final class S3Xml {

  private S3Xml() {}

  /** The root element of {@code body}. */
  static Element parse(byte[] body) throws IOException {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      DocumentBuilder b = f.newDocumentBuilder();
      // Fail on the first error without printing it: the caller says what was wrong with the answer.
      b.setErrorHandler(new DefaultHandler() {
        @Override
        public void fatalError(SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
          throw e;
        }
      });
      return b.parse(new ByteArrayInputStream(body)).getDocumentElement();
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("not well-formed XML", e);
    }
  }

  static String name(Node n) {
    return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
  }

  /** The child elements of {@code e} named {@code name}. */
  static List<Element> children(Element e, String name) {
    List<Element> out = new ArrayList<>();
    for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element el && name(el).equals(name)) {
        out.add(el);
      }
    }
    return out;
  }

  /** The first child element named {@code name}, or {@code null}. */
  static Element child(Element e, String name) {
    for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element el && name(el).equals(name)) {
        return el;
      }
    }
    return null;
  }

  /** The text of the first child element named {@code name}, or {@code null}. */
  static String text(Element e, String name) {
    Element c = child(e, name);
    return c == null ? null : c.getTextContent();
  }
}
