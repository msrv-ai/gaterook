package com.gaterook.connectors.redshift;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorException.Kind;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Redshift Data API (version 2019-12-20) on {@code java.net.http}: {@code POST /} to the region's endpoint with
 * {@code X-Amz-Target: RedshiftData.<Operation>}, JSON 1.1, signed with SigV4 for {@code redshift-data}, every header
 * the request carries included. The host is fixed by the region (a development build's test endpoint
 * {@code aws.redshift-data} replaces it, with the test credentials that go with it); redirects are never followed and
 * nothing is retried. Each request runs against the call's deadline, and an answer is read up to {@link #MAX_BODY}.
 *
 * <p>The setup helpers list what the form can name the same way, through the other APIs of {@link Service}: Redshift's
 * own Query API ({@code DescribeClusters}, XML), Redshift Serverless and Secrets Manager (JSON 1.1), each on its
 * region's endpoint or its own test endpoint ({@code aws.redshift}, {@code aws.redshift-serverless},
 * {@code aws.secretsmanager}).
 */
final class RedshiftApi {

  static final String TARGET = "RedshiftData.";
  static final String CONTENT_TYPE = "application/x-amz-json-1.1";
  static final String SIGNING_NAME = "redshift-data";
  /** The Query API's form: {@code Action=DescribeClusters&Version=2012-12-01}. */
  static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";
  static final String QUERY_VERSION = "2012-12-01";
  /** A page of GetStatementResult; its typed JSON is a few times the rows' bytes. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(35);
  private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * An AWS API the connector calls: its endpoint and signing name, the {@code X-Amz-Target} prefix of a JSON API
   * ({@code null} for the Query API) and how texts name it.
   */
  enum Service {
    DATA("redshift-data", TARGET, "Redshift", "the Redshift Data API"),
    REDSHIFT("redshift", null, "Redshift", "Amazon Redshift"),
    SERVERLESS("redshift-serverless", "RedshiftServerless.", "Redshift Serverless", "Redshift Serverless"),
    SECRETS("secretsmanager", "secretsmanager.", "Secrets Manager", "Secrets Manager");

    final String name;
    final String target;
    final String shortName;
    final String longName;

    Service(String name, String target, String shortName, String longName) {
      this.name = name;
      this.target = target;
      this.shortName = shortName;
      this.longName = longName;
    }
  }

  /** One answer: its status and body. */
  private record Exchange(int status, byte[] body) {}

  private final CloudCredentials cloud;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  RedshiftApi(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to the Data API: the connection, its settings and secrets, the call's context. */
  record Call(ConnectionConfig cfg, RedshiftSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /** The endpoint of the connection's region, or the test endpoint of a development build. */
  URI endpoint(RedshiftSettings s) {
    return endpoint(Service.DATA, s);
  }

  /** {@code https://<service>.<region>.amazonaws.com/} ({@code .com.cn} in China), or the service's test endpoint. */
  URI endpoint(Service service, RedshiftSettings s) {
    URI test = cloud.endpoints().service("aws." + service.name);
    if (test != null) {
      return test;
    }
    return URI.create("https://" + service.name + "." + s.region() + ".amazonaws.com"
        + (s.region().startsWith("cn-") ? ".cn" : "") + "/");
  }

  /**
   * Runs {@code operation} with {@code body} under {@code sessionPolicy} (with a role) and returns the answer.
   *
   * @param change whether the request starts a change: a connection lost after sending it leaves the outcome unknown
   */
  JsonNode call(Call call, String operation, ObjectNode body, String sessionPolicy, boolean change)
      throws ConnectorException {
    return json(call, Service.DATA, operation, body, sessionPolicy, change);
  }

  /** A read of a JSON 1.1 API other than the Data API ({@link Service#SERVERLESS}, {@link Service#SECRETS}). */
  JsonNode read(Call call, Service service, String operation, ObjectNode body, String sessionPolicy)
      throws ConnectorException {
    return json(call, service, operation, body, sessionPolicy, false);
  }

  private JsonNode json(Call call, Service service, String operation, ObjectNode body, String sessionPolicy,
      boolean change) throws ConnectorException {
    byte[] json = JSON.writeValueAsBytes(body);
    Exchange x = exchange(call, service, operation, CONTENT_TYPE, service.target + operation, json, sessionPolicy,
        change);
    JsonNode tree;
    try {
      tree = JSON.readTree(x.body().length == 0 ? "{}" : new String(x.body(), StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(service.shortName + " answered " + x.status() + " to " + operation
          + " with something that isn't JSON.", null);
    }
    if (x.status() / 100 != 2) {
      throw service == Service.DATA ? error(x.status(), tree, operation, call, change)
          : refusal(service, x.status(), tree.path("__type").asString(""), tree.path("message").asString(
              tree.path("Message").asString("")), operation, call);
    }
    return tree;
  }

  /**
   * One read of Redshift's Query API ({@code Action=DescribeClusters}): the root element of its XML answer.
   *
   * @param params the operation's parameters, after {@code Action} and {@code Version}
   */
  Element query(Call call, String action, Map<String, String> params, String sessionPolicy)
      throws ConnectorException {
    StringBuilder form = new StringBuilder("Action=").append(encode(action)).append("&Version=").append(QUERY_VERSION);
    params.forEach((k, v) -> form.append('&').append(encode(k)).append('=').append(encode(v)));
    Exchange x = exchange(call, Service.REDSHIFT, action, FORM, null, form.toString().getBytes(StandardCharsets.UTF_8),
        sessionPolicy, false);
    Element root;
    try {
      root = Xml.parse(x.body());
    } catch (IOException e) {
      if (x.status() / 100 != 2) {
        throw refusal(Service.REDSHIFT, x.status(), "", "", action, call);
      }
      throw ConnectorException.resourceError("Redshift answered " + x.status() + " to " + action + " with something "
          + "that isn't XML.", null);
    }
    if (x.status() / 100 != 2) {
      Element error = Xml.child(root, "Error");
      String code = error == null ? null : Xml.text(error, "Code");
      String message = error == null ? null : Xml.text(error, "Message");
      throw refusal(Service.REDSHIFT, x.status(), code == null ? "" : code, message == null ? "" : message, action,
          call);
    }
    return root;
  }

  /** Sends one signed request and reads its answer; a failure before an answer is an exception. */
  private Exchange exchange(Call call, Service service, String operation, String contentType, String target,
      byte[] body, String sessionPolicy, boolean change) throws ConnectorException {
    URI uri = endpoint(service, call.settings());
    Duration left = call.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(service.shortName + " didn't answer " + operation + " in time.");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (target != null) {
      request.header("X-Amz-Target", target);
    }
    sign(call, request, uri, service, contentType, target, body, sessionPolicy);
    try {
      HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        return new Exchange(response.statusCode(), read(in));
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + service.longName + " in " + call.settings().region()
          + " (timed out). Check that this computer can reach AWS.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Redshift didn't answer the change in time, so whether it ran is not "
            + "known. Check the table before retrying.", e);
      }
      throw ConnectorException.timeout(service.shortName + " didn't answer " + operation + " in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to " + service.longName + " in " + call.settings().region()
          + ": " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to Redshift failed during the change, so whether it "
            + "ran is not known. Check the table before retrying.", e);
      }
      throw ConnectorException.unavailable(service.shortName + " " + operation + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(service.shortName + " " + operation + " was cancelled.");
    }
  }

  /** SigV4 for the service: the signer's headers onto the request {@code java.net.http} sends. */
  private void sign(Call call, HttpRequest.Builder b, URI uri, Service service, String contentType, String target,
      byte[] body, String sessionPolicy) throws ConnectorException {
    AwsCredentials credentials;
    try {
      credentials = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(call.cfg().id(),
          call.cfg().version(), call.cfg().name()), call.settings().credentials(), sessionPolicy, call.secrets()),
          call.ctx()::taint).resolveCredentials();
    } catch (SdkException e) {
      throw ConnectorException.unavailable("The AWS credentials of " + call.cfg().name() + " couldn't be loaded: "
          + firstLine(e.getMessage()), e);
    }
    SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder().method(SdkHttpMethod.POST).uri(uri)
        .putHeader("Content-Type", contentType);
    if (target != null) {
      unsigned.putHeader("X-Amz-Target", target);
    }
    SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(credentials).request(unsigned.build())
        .payload(ContentStreamProvider.fromByteArrayUnsafe(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service.name)
        .putProperty(AwsV4HttpSigner.REGION_NAME, call.settings().region()));
    for (Map.Entry<String, List<String>> h : signed.request().headers().entrySet()) {
      String name = h.getKey().toLowerCase(Locale.ROOT);
      if (RESTRICTED.contains(name) || name.equals("content-type") || name.equals("x-amz-target")) {
        continue;
      }
      for (String v : h.getValue()) {
        b.header(h.getKey(), v);
      }
    }
  }

  /** A refusal of an API other than the Data API, as what to do about it. */
  private static ConnectorException refusal(Service service, int status, String type, String message,
      String operation, Call call) {
    String code = type.substring(type.lastIndexOf('#') + 1);
    String said = OutputSanitizer.sanitize(firstLine(message));
    if (said.isBlank()) {
      said = code.isBlank() ? "HTTP " + status : code;
    }
    return switch (code) {
      case "AccessDeniedException", "AccessDenied", "UnauthorizedOperation" -> ConnectorException.resourceError(
          "The AWS credentials of " + call.cfg().name() + " may not call " + service.name + ":" + operation + ": "
              + said + ".", null);
      case "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException", "ExpiredToken",
          "IncompleteSignatureException", "MissingAuthenticationTokenException", "InvalidClientTokenId",
          "SignatureDoesNotMatch" -> ConnectorException.unavailable("AWS refused the credentials of " + call.cfg().name()
          + ": " + said + ".", null);
      case "ThrottlingException", "Throttling" -> ConnectorException.resourceError(service.longName + " throttled "
          + "the request: " + said + ". Try again in a moment.", null);
      default -> status >= 500
          ? ConnectorException.unavailable(service.shortName + " " + operation + " failed with " + status + ": "
              + said + ".", null)
          : ConnectorException.resourceError(service.shortName + " refused " + operation + " (" + status + "): "
              + said + ".", null);
    };
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
  }

  /**
   * Redshift's XML answers, read by local element names. The parser refuses a DOCTYPE outright and never resolves an
   * external entity, DTD, schema or XInclude: an answer is data from a server, never a reason to open another file or
   * connection.
   */
  static final class Xml {

    private Xml() {}

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

    /** The child elements of {@code e} named {@code name}. */
    static List<Element> children(Element e, String name) {
      List<Element> out = new ArrayList<>();
      for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
        if (c instanceof Element el && name.equals(el.getLocalName() != null ? el.getLocalName() : el.getNodeName())) {
          out.add(el);
        }
      }
      return out;
    }

    /** The first child element named {@code name}, or {@code null}. */
    static Element child(Element e, String name) {
      List<Element> found = children(e, name);
      return found.isEmpty() ? null : found.getFirst();
    }

    /** The text of the first child element named {@code name}, stripped, or {@code null}. */
    static String text(Element e, String name) {
      Element c = child(e, name);
      return c == null ? null : c.getTextContent().strip();
    }
  }

  /** A refusal of the Data API, with its error type ({@code ValidationException}) for the callers that act on it. */
  static final class ApiError extends ConnectorException {
    private static final long serialVersionUID = 1L;
    private final String type;

    ApiError(Kind kind, String message, String type) {
      super(kind, message, null);
      this.type = type;
    }

    String type() {
      return type;
    }
  }

  /** The Data API's {@code __type} and message as what the agent can do about it. */
  static ApiError error(int status, JsonNode tree, String operation, Call call, boolean change) {
    String type = tree.path("__type").asString("");
    type = type.substring(type.lastIndexOf('#') + 1);
    String message = OutputSanitizer.sanitize(firstLine(tree.path("message").asString(
        tree.path("Message").asString(""))));
    String said = message.isBlank() ? type : message;
    String who = call.cfg().name();
    RedshiftSettings s = call.settings();
    return switch (type) {
      case "ValidationException" -> new ApiError(Kind.BAD_INPUT, "Redshift refused " + operation + ": " + said + ".",
          type);
      case "ResourceNotFoundException" -> new ApiError(Kind.BAD_INPUT, said + ". Check the " + s.warehouse()
          + " and the database " + s.database() + "; Redshift keeps a query's result for 24 hours.", type);
      case "ActiveStatementsExceededException", "ActiveSessionsExceededException",
          "ActiveWaitingRequestsExceededException" -> new ApiError(Kind.RESOURCE_ERROR, "Redshift runs too many "
          + "statements now: " + said + ". Try again in a moment.", type);
      case "DatabaseConnectionException" -> new ApiError(Kind.RESOURCE_UNAVAILABLE, "The Data API couldn't connect "
          + "to the database " + s.database() + " on " + s.warehouse() + ": " + said + ". Check that it is available "
          + "and that " + s.signIn() + " may sign in.", type);
      case "QueryTimeoutException" -> new ApiError(Kind.TIMEOUT, "Redshift didn't answer " + operation + " in time: "
          + said + ".", type);
      case "AccessDeniedException" -> new ApiError(Kind.RESOURCE_ERROR, "The AWS credentials of " + who + " may not "
          + "do this: " + said + ".", type);
      case "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException",
          "IncompleteSignatureException", "MissingAuthenticationTokenException", "InvalidClientTokenId" ->
          new ApiError(Kind.RESOURCE_UNAVAILABLE, "AWS refused the credentials of " + who + ": " + said + ".", type);
      case "ThrottlingException" -> new ApiError(Kind.RESOURCE_ERROR, "The Data API throttled the request: " + said
          + ". Try again in a moment.", type);
      default -> status >= 500
          ? (change ? new ApiError(Kind.OUTCOME_UNKNOWN, "Redshift failed with " + status + " (" + type + ") during "
              + "the change, so whether it ran is not known. Check the table before retrying.", type)
              : new ApiError(Kind.RESOURCE_UNAVAILABLE, "Redshift " + operation + " failed with " + status + ": "
                  + said + ".", type))
          : new ApiError(Kind.RESOURCE_ERROR, "Redshift refused " + operation + " (" + status + "): " + said + ".",
              type);
    };
  }

  private static byte[] read(InputStream in) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw ConnectorException.resourceError("Redshift's answer is larger than " + (MAX_BODY >> 20) + " MiB.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : firstLine(m);
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }
}
