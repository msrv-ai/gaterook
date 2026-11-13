package com.gaterook.connectors.azure;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The XML answers of Azure Storage (blob and file lists, queue messages, errors) as a small tree. The JDK's StAX
 * reader with DTDs and external entities off: only the five predefined entities and character references expand, so a
 * document can name no file, URL or entity of its own. Bounded: 32 levels, 100,000 elements.
 */
final class Xml {

  static final int MAX_DEPTH = 32;
  static final int MAX_ELEMENTS = 100_000;

  /** One element: its local name, its text (whitespace kept), its child elements. */
  record Node(String name, String text, List<Node> children) {

    /** The first child named {@code name}, or {@code null}. */
    Node child(String name) {
      for (Node c : children) {
        if (c.name.equals(name)) {
          return c;
        }
      }
      return null;
    }

    /** Every child named {@code name}. */
    List<Node> all(String name) {
      List<Node> out = new ArrayList<>();
      for (Node c : children) {
        if (c.name.equals(name)) {
          out.add(c);
        }
      }
      return out;
    }

    /** The text of the child at {@code path} ({@code Properties/Content-Length}), or {@code null}. */
    String text(String path) {
      Node n = this;
      for (String part : path.split("/")) {
        n = n.child(part);
        if (n == null) {
          return null;
        }
      }
      return n.text;
    }
  }

  private Xml() {}

  /**
   * The document's root element.
   *
   * @throws IllegalArgumentException when the bytes aren't a well-formed document within the bounds
   */
  static Node parse(byte[] bytes) {
    int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
    XMLInputFactory f = XMLInputFactory.newDefaultFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
    f.setProperty(XMLInputFactory.IS_COALESCING, true);
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    XMLStreamReader r = null;
    try {
      r = f.createXMLStreamReader(new ByteArrayInputStream(bytes, start, bytes.length - start));
      Deque<Builder> stack = new ArrayDeque<>();
      Node root = null;
      int elements = 0;
      while (r.hasNext()) {
        int event = r.next();
        switch (event) {
          case XMLStreamConstants.START_ELEMENT -> {
            if (++elements > MAX_ELEMENTS || stack.size() >= MAX_DEPTH) {
              throw new IllegalArgumentException("The XML answer is larger or deeper than GateRook reads.");
            }
            stack.push(new Builder(r.getLocalName()));
          }
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
            if (!stack.isEmpty()) {
              stack.peek().text.append(r.getText());
            }
          }
          case XMLStreamConstants.END_ELEMENT -> {
            Builder done = stack.pop();
            Node node = new Node(done.name, done.text.toString(), List.copyOf(done.children));
            if (stack.isEmpty()) {
              root = node;
            } else {
              stack.peek().children.add(node);
            }
          }
          case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
              throw new IllegalArgumentException("The XML answer declares a DTD or an entity.");
          default -> {
            // Comments, processing instructions and the declaration carry nothing GateRook reads.
          }
        }
      }
      if (root == null) {
        throw new IllegalArgumentException("The XML answer has no root element.");
      }
      return root;
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("The XML answer isn't well-formed: " + e.getMessage(), e);
    } finally {
      if (r != null) {
        try {
          r.close();
        } catch (XMLStreamException e) {
          // Nothing to release beyond the reader itself.
        }
      }
    }
  }

  private static final class Builder {
    final String name;
    final StringBuilder text = new StringBuilder();
    final List<Node> children = new ArrayList<>();

    Builder(String name) {
      this.name = name;
    }
  }
}
