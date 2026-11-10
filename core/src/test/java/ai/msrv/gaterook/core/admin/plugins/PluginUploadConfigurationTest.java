package ai.msrv.gaterook.core.admin.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

/**
 * The dispatcher wraps a multipart request without parsing it: parts are read only when the plugin upload's controller
 * asks for its file, after the local guard checked the signature, never for a request nobody authenticated.
 */
class PluginUploadConfigurationTest {

  @Test
  void partsAreParsedOnlyWhenTheControllerReadsThem() throws Exception {
    MultipartResolver resolver = new PluginUploadConfiguration().multipartResolver();
    AtomicInteger parsed = new AtomicInteger();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/dev") {
      @Override
      public Collection<Part> getParts() {
        parsed.incrementAndGet();
        return java.util.List.of();
      }
    };
    request.setContentType("multipart/form-data; boundary=x");

    assertThat(resolver.isMultipart(request)).isTrue();
    MultipartHttpServletRequest wrapped = resolver.resolveMultipart(request);
    assertThat(parsed.get()).as("nothing parsed when the dispatcher wraps the request").isZero();
    wrapped.getFileMap();
    assertThat(parsed.get()).as("parsed when the controller asks for the file").isEqualTo(1);
  }
}
