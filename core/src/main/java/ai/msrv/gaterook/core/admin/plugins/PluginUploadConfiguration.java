package ai.msrv.gaterook.core.admin.plugins;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * A plugin JAR is uploaded as multipart (ARCHITECTURE.md §6: {@code POST /api/plugins/check} multipart ≤ 50 MB), so the
 * servlet's multipart limit is raised to just over that. It is the only multipart route; everything else stays JSON.
 *
 * <p>Parts are parsed lazily: only when the upload's controller reads its file, after the local guard checked the
 * request's signature, never by the dispatcher for a request nobody authenticated (the guards refuse multipart on
 * every other route, on both listeners, before it gets that far).
 */
@Configuration(proxyBeanMethods = false)
class PluginUploadConfiguration {

  private static final long MB = 1024L * 1024;

  @Bean
  MultipartConfigElement multipartConfigElement() {
    // location, maxFileSize, maxRequestSize, fileSizeThreshold.
    return new MultipartConfigElement("", 51 * MB, 52 * MB, 0);
  }

  /** Boot's resolver backs off for this one: the same servlet parts, parsed when first read. */
  @Bean(name = DispatcherServlet.MULTIPART_RESOLVER_BEAN_NAME)
  MultipartResolver multipartResolver() {
    StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
    resolver.setResolveLazily(true);
    return resolver;
  }
}
