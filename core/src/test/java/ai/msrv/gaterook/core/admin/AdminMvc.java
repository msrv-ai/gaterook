package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.TestHttp;
import jakarta.servlet.Filter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The admin API through MockMvc as Electron's main process calls it: every request passes the core's own servlet
 * filters (the local guard, the locked mode), carries {@code Host: 127.0.0.1:<port>} and is signed with the launch
 * secret ({@code X-Gateway-Auth}) over its method, path and query. Every answer is kept, so a test can scan all of
 * them at the end (no secret value, ARCHITECTURE.md §9 "Write-only secrets").
 */
public final class AdminMvc {

  /** Names a property that carries a secret value may have; none of them holds a scalar in any response. */
  public static final Set<String> SECRET_PROPERTIES = Set.of("value", "password", "passphrase", "secret", "token",
      "privateKey", "apiKey", "secretAccessKey", "fileKey", "launchSecret", "clientSecret", "accessToken",
      "refreshToken");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One answer: method, target, status, body. */
  public record Answer(String method, String target, int status, String contentType, String body) {

    public JsonNode json() {
      return body == null || body.isEmpty() ? MissingNode.getInstance() : JSON.readTree(body);
    }
  }

  private final MockMvc signed;
  private final MockMvc unsigned;
  private final int port;
  private final List<Answer> answers = new CopyOnWriteArrayList<>();

  public AdminMvc(ConfigurableApplicationContext context, int port) {
    this.port = port;
    List<Filter> filters = new ArrayList<>();
    context.getBeanProvider(FilterRegistrationBean.class).orderedStream()
        .forEach(r -> filters.add((Filter) r.getFilter()));
    LaunchSecret secret = TestGateway.launchSecret();
    RequestPostProcessor local = request -> {
      request.setLocalPort(port);
      request.setServerPort(port);
      request.setRemoteAddr("127.0.0.1");
      request.addHeader("Host", "127.0.0.1:" + port);
      return request;
    };
    RequestPostProcessor sign = request -> {
      String target = request.getQueryString() == null ? request.getRequestURI()
          : request.getRequestURI() + "?" + request.getQueryString();
      request.addHeader(LaunchSecret.HEADER, secret.sign(request.getMethod(), target, System.currentTimeMillis(),
          TestHttp.nonce()));
      return request;
    };
    WebApplicationContext web = (WebApplicationContext) context;
    this.signed = MockMvcBuilders.webAppContextSetup(web).addFilters(filters.toArray(Filter[]::new))
        .defaultRequest(MockMvcRequestBuilders.get("/").with(local).with(sign))
        .alwaysDo(this::keep).build();
    this.unsigned = MockMvcBuilders.webAppContextSetup(web).addFilters(filters.toArray(Filter[]::new))
        .defaultRequest(MockMvcRequestBuilders.get("/").with(local)).build();
  }

  public int port() {
    return port;
  }

  public Answer get(String target) {
    return send(HttpMethod.GET, target, null);
  }

  public Answer post(String target, Object body) {
    return send(HttpMethod.POST, target, body);
  }

  public Answer put(String target, Object body) {
    return send(HttpMethod.PUT, target, body);
  }

  public Answer delete(String target, Object body) {
    return send(HttpMethod.DELETE, target, body);
  }

  /** A signed request; {@code body} is JSON text, a tree or {@code null}. */
  public Answer send(HttpMethod method, String target, Object body) {
    return perform(signed, method, target, body);
  }

  /** The same request without a signature: what any other program on this computer could send. */
  public Answer sendUnsigned(HttpMethod method, String target, Object body) {
    return perform(unsigned, method, target, body);
  }

  /** {@code get} that must answer 200, as JSON. */
  public JsonNode ok(String target) {
    Answer a = get(target);
    assertThat(a.status()).as("GET " + target + ": " + a.body()).isEqualTo(200);
    return a.json();
  }

  /** {@code method} that must answer {@code status}, as JSON. */
  public JsonNode expect(int status, HttpMethod method, String target, Object body) {
    Answer a = send(method, target, body);
    assertThat(a.status()).as(method + " " + target + ": " + a.body()).isEqualTo(status);
    return a.json();
  }

  public List<Answer> answers() {
    return List.copyOf(answers);
  }

  private Answer perform(MockMvc mvc, HttpMethod method, String target, Object body) {
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, URI.create(target));
    if (body != null) {
      String text = body instanceof String s ? s : JSON.writeValueAsString(body);
      request.contentType(MediaType.APPLICATION_JSON).content(text.getBytes(StandardCharsets.UTF_8));
    }
    try {
      MvcResult r = mvc.perform(request).andReturn();
      return new Answer(method.name(), target, r.getResponse().getStatus(), r.getResponse().getContentType(),
          r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new AssertionError(method + " " + target + " failed in the test harness", e);
    }
  }

  private void keep(MvcResult r) throws Exception {
    MockHttpServletRequest req = r.getRequest();
    String target = req.getQueryString() == null ? req.getRequestURI() : req.getRequestURI() + "?"
        + req.getQueryString();
    answers.add(new Answer(req.getMethod(), target, r.getResponse().getStatus(), r.getResponse().getContentType(),
        r.getResponse().getContentAsString(StandardCharsets.UTF_8)));
  }

  /**
   * Properties named like a secret value that hold a string or a number, anywhere in {@code node}, as JSON Pointers.
   * A schema describing such a field ({@code "password": {"type": "string", "writeOnly": true}}) is an object, and a
   * flag ({@code "passphrase": true}: the backup has one) is a boolean; neither is a value.
   */
  public static List<String> secretLookingValues(JsonNode node) {
    List<String> out = new ArrayList<>();
    walk(node, "", out);
    return out;
  }

  private static void walk(JsonNode node, String pointer, List<String> out) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        String child = pointer + "/" + e.getKey();
        if (SECRET_PROPERTIES.contains(e.getKey()) && e.getValue().isValueNode() && !e.getValue().isNull()
            && !e.getValue().isBoolean()) {
          out.add(child);
        }
        walk(e.getValue(), child, out);
      }
    } else if (node.isArray()) {
      Iterator<JsonNode> it = node.iterator();
      for (int i = 0; it.hasNext(); i++) {
        walk(it.next(), pointer + "/" + i, out);
      }
    }
  }
}
