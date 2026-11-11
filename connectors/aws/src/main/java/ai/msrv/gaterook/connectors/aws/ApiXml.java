package ai.msrv.gaterook.connectors.aws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The XML of awsQuery, ec2Query and restXml, guided by the shapes: answers to JSON under the model's member names
 * (element names from {@code xmlName}, wrapped and flattened lists and maps, attributes, typed scalars, timestamps as
 * ISO-8601), and restXml request bodies from canonical parameters. The parser refuses DTDs and external entities.
 */
final class ApiXml {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ApiXml() {}

  /** Parses an answer; DOCTYPE, external entities and XInclude are refused. */
  static Element parse(byte[] body) throws IOException {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      DocumentBuilder b = f.newDocumentBuilder();
      // Fail on the first error without printing it: an answer that isn't XML is reported by the caller.
      b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }
      });
      Document d = b.parse(new ByteArrayInputStream(body));
      return d.getDocumentElement();
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("not well-formed XML: " + e.getMessage(), e);
    }
  }

  static String localName(Node n) {
    return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
  }

  /** The child elements of {@code e} named {@code name}. */
  static List<Element> children(Element e, String name) {
    List<Element> out = new ArrayList<>();
    for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element el && (name == null || localName(el).equals(name))) {
        out.add(el);
      }
    }
    return out;
  }

  static Element child(Element e, String name) {
    for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element el && localName(el).equals(name)) {
        return el;
      }
    }
    return null;
  }

  /** The element name of a structure member: its {@code xmlName}, else its name. */
  static String elementName(String member, JsonNode trait) {
    return trait.hasNonNull("xml") ? trait.get("xml").asString() : member;
  }

  /** The members of {@code element}, read as structure {@code shape}. */
  static ObjectNode structure(ApiModel m, JsonNode shape, Element element) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
      JsonNode member = e.getValue();
      if (member.has("header") || member.has("prefixHeaders") || member.has("status") || member.has("payload")) {
        continue;
      }
      String name = elementName(e.getKey(), member);
      JsonNode target = m.shape(member.path("s").asString());
      if (member.path("attr").asInt(0) == 1) {
        if (element.hasAttribute(name)) {
          out.set(e.getKey(), scalar(target, element.getAttribute(name)));
        }
        continue;
      }
      String type = ApiModel.type(target);
      boolean flat = member.path("flat").asInt(0) == 1;
      if (flat && (type.equals("list") || type.equals("map"))) {
        List<Element> items = children(element, name);
        if (!items.isEmpty()) {
          out.set(e.getKey(), type.equals("list") ? flatList(m, target, items) : flatMap(m, target, items));
        }
        continue;
      }
      Element c = child(element, name);
      if (c != null) {
        out.set(e.getKey(), value(m, target, c));
      }
    }
    return out;
  }

  /** {@code element} read as {@code shape}. */
  static JsonNode value(ApiModel m, JsonNode shape, Element element) {
    switch (ApiModel.type(shape)) {
      case "structure" -> {
        return structure(m, shape, element);
      }
      case "union" -> {
        ObjectNode all = structure(m, shape, element);
        return all;
      }
      case "list" -> {
        JsonNode member = shape.path("member");
        String item = member.hasNonNull("xml") ? member.get("xml").asString() : "member";
        JsonNode target = m.shape(member.path("s").asString());
        ArrayNode out = F.arrayNode();
        for (Element c : children(element, item)) {
          out.add(value(m, target, c));
        }
        return out;
      }
      case "map" -> {
        JsonNode key = shape.path("key");
        JsonNode val = shape.path("value");
        String k = key.hasNonNull("xml") ? key.get("xml").asString() : "key";
        String v = val.hasNonNull("xml") ? val.get("xml").asString() : "value";
        JsonNode target = m.shape(val.path("s").asString());
        ObjectNode out = F.objectNode();
        for (Element entry : children(element, "entry")) {
          Element ke = child(entry, k);
          Element ve = child(entry, v);
          if (ke != null) {
            out.set(ke.getTextContent(), ve == null ? F.nullNode() : value(m, target, ve));
          }
        }
        return out;
      }
      default -> {
        return scalar(shape, element.getTextContent());
      }
    }
  }

  private static ArrayNode flatList(ApiModel m, JsonNode list, List<Element> items) {
    JsonNode target = m.shape(list.path("member").path("s").asString());
    ArrayNode out = F.arrayNode();
    for (Element c : items) {
      out.add(value(m, target, c));
    }
    return out;
  }

  private static ObjectNode flatMap(ApiModel m, JsonNode map, List<Element> entries) {
    JsonNode key = map.path("key");
    JsonNode val = map.path("value");
    String k = key.hasNonNull("xml") ? key.get("xml").asString() : "key";
    String v = val.hasNonNull("xml") ? val.get("xml").asString() : "value";
    JsonNode target = m.shape(val.path("s").asString());
    ObjectNode out = F.objectNode();
    for (Element entry : entries) {
      Element ke = child(entry, k);
      Element ve = child(entry, v);
      if (ke != null) {
        out.set(ke.getTextContent(), ve == null ? F.nullNode() : value(m, target, ve));
      }
    }
    return out;
  }

  /** A scalar's text as JSON of its type; text that isn't one stays text. */
  static JsonNode scalar(JsonNode shape, String text) {
    String t = text == null ? "" : text.strip();
    try {
      switch (ApiModel.type(shape)) {
        case "boolean" -> {
          return t.equalsIgnoreCase("true") ? F.booleanNode(true) : t.equalsIgnoreCase("false")
              ? F.booleanNode(false) : F.stringNode(text);
        }
        case "byte", "short", "integer", "long" -> {
          return F.numberNode(Long.parseLong(t));
        }
        case "bigInteger" -> {
          return F.numberNode(new BigInteger(t));
        }
        case "float", "double" -> {
          return switch (t) {
            case "NaN", "Infinity", "-Infinity" -> F.stringNode(t);
            default -> F.numberNode(Double.parseDouble(t));
          };
        }
        case "bigDecimal" -> {
          return F.numberNode(new BigDecimal(t));
        }
        case "timestamp" -> {
          return ApiJson.timestamp(F.stringNode(t));
        }
        default -> {
          return F.stringNode(text == null ? "" : text);
        }
      }
    } catch (NumberFormatException e) {
      return F.stringNode(text);
    }
  }

  // ── restXml request bodies ──────────────────────────────────────────────────────────────────────────────────

  /** {@code <name xmlns="…">members</name>} of a structure {@code shape} with canonical {@code value}. */
  static String document(ApiModel m, String name, JsonNode shape, JsonNode value, JsonNode namespace) {
    StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    element(m, out, name, shape, value, namespace, true);
    return out.toString();
  }

  private static void element(ApiModel m, StringBuilder out, String name, JsonNode shape, JsonNode value,
      JsonNode namespace, boolean root) {
    out.append('<').append(name);
    JsonNode ns = namespace != null && !namespace.isMissingNode() ? namespace : shape.path("ns");
    if (ns.isObject() && ns.hasNonNull("uri")) {
      out.append(ns.hasNonNull("prefix") ? " xmlns:" + ns.get("prefix").asString() : " xmlns").append("=\"")
          .append(escape(ns.get("uri").asString())).append('"');
    } else if (root && m.xmlNamespace != null) {
      out.append(" xmlns=\"").append(escape(m.xmlNamespace)).append('"');
    }
    String type = ApiModel.type(shape);
    if (type.equals("structure") || type.equals("union")) {
      for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
        JsonNode member = e.getValue();
        JsonNode v = value.get(e.getKey());
        if (v != null && member.path("attr").asInt(0) == 1) {
          out.append(' ').append(elementName(e.getKey(), member)).append("=\"")
              .append(escape(text(m.shape(member.path("s").asString()), v, member))).append('"');
        }
      }
      out.append('>');
      for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
        JsonNode member = e.getValue();
        JsonNode v = value.get(e.getKey());
        if (v == null || v.isNull() || member.path("attr").asInt(0) == 1 || member.has("label")
            || member.has("query") || member.has("header") || member.has("prefixHeaders")
            || member.has("queryParams")) {
          continue;
        }
        member(m, out, elementName(e.getKey(), member), member, v);
      }
    } else {
      out.append('>');
      out.append(escape(text(shape, value, null)));
    }
    out.append("</").append(name).append('>');
  }

  private static void member(ApiModel m, StringBuilder out, String name, JsonNode member, JsonNode value) {
    JsonNode target = m.shape(member.path("s").asString());
    String type = ApiModel.type(target);
    boolean flat = member.path("flat").asInt(0) == 1;
    switch (type) {
      case "list" -> {
        JsonNode item = target.path("member");
        String itemName = item.hasNonNull("xml") ? item.get("xml").asString() : "member";
        if (!flat) {
          out.append('<').append(name).append(nsAttr(member.path("ns"))).append('>');
        }
        for (JsonNode v : value) {
          member(m, out, flat ? name : itemName, item, v);
        }
        if (!flat) {
          out.append("</").append(name).append('>');
        }
      }
      case "map" -> {
        JsonNode key = target.path("key");
        JsonNode val = target.path("value");
        String k = key.hasNonNull("xml") ? key.get("xml").asString() : "key";
        String v = val.hasNonNull("xml") ? val.get("xml").asString() : "value";
        if (!flat) {
          out.append('<').append(name).append('>');
        }
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          out.append('<').append(flat ? name : "entry").append('>');
          out.append('<').append(k).append('>').append(escape(e.getKey())).append("</").append(k).append('>');
          member(m, out, v, val, e.getValue());
          out.append("</").append(flat ? name : "entry").append('>');
        }
        if (!flat) {
          out.append("</").append(name).append('>');
        }
      }
      default -> element(m, out, name, target, value, member.path("ns"), false);
    }
  }

  private static String nsAttr(JsonNode ns) {
    if (!ns.isObject() || !ns.hasNonNull("uri")) {
      return "";
    }
    return (ns.hasNonNull("prefix") ? " xmlns:" + ns.get("prefix").asString() : " xmlns") + "=\""
        + escape(ns.get("uri").asString()) + "\"";
  }

  /** A canonical scalar as XML or query text; timestamps as ISO-8601 unless the member or shape says otherwise. */
  static String text(JsonNode shape, JsonNode value, JsonNode member) {
    if (ApiModel.type(shape).equals("timestamp")) {
      java.time.Instant t = java.time.Instant.parse(value.asString());
      String format = member != null && member.hasNonNull("ts") ? member.get("ts").asString()
          : shape.path("ts").asString("date-time");
      return switch (format) {
        case "epoch-seconds" -> ApiJson.epochSeconds(t).toPlainString();
        case "http-date" -> ApiJson.HTTP_DATE.format(t);
        default -> t.toString();
      };
    }
    if (value.isNumber()) {
      return value.isFloatingPointNumber() && !ApiModel.type(shape).equals("bigDecimal")
          ? floating(value.doubleValue()) : value.numberValue().toString();
    }
    return value.asString();
  }

  private static String floating(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
  }

  static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '&' -> out.append("&amp;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        case '\r' -> out.append("&#xD;");
        case '\n' -> out.append("&#xA;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
