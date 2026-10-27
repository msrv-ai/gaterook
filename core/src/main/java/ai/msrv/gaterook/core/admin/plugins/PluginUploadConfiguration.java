package ai.msrv.gaterook.core.admin.plugins;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A plugin JAR is uploaded as multipart (ARCHITECTURE.md §6: {@code POST /api/plugins/check} multipart ≤ 50 MB), so the
 * servlet's multipart limit is raised to just over that. It is the only multipart route; everything else stays JSON.
 */
@Configuration(proxyBeanMethods = false)
class PluginUploadConfiguration {

  private static final long MB = 1024L * 1024;

  @Bean
  MultipartConfigElement multipartConfigElement() {
    // location, maxFileSize, maxRequestSize, fileSizeThreshold.
    return new MultipartConfigElement("", 51 * MB, 52 * MB, 0);
  }
}
