package ai.msrv.gaterook.connectors.kubernetes;

import java.util.regex.Pattern;

/**
 * The shapes of names the agent may put into a request path or query. Names go into the URL percent-encoded anyway;
 * checking them first gives the agent a clear answer and keeps {@code .} and {@code ..} — path segments every HTTP
 * stack normalizes — out of the path.
 */
final class K8sNames {

  private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
  /** DNS subdomains, plus {@code :} and {@code @} of RBAC names ({@code system:controller:…}). */
  private static final Pattern OBJECT = Pattern.compile("^[A-Za-z0-9]([-A-Za-z0-9._:@]*[A-Za-z0-9])?$");
  private static final Pattern CONTAINER = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

  private K8sNames() {}

  static boolean namespace(String s) {
    return s != null && s.length() <= 63 && NAMESPACE.matcher(s).matches();
  }

  static boolean object(String s) {
    return s != null && s.length() <= 253 && OBJECT.matcher(s).matches() && !s.contains("..");
  }

  static boolean container(String s) {
    return s != null && s.length() <= 63 && CONTAINER.matcher(s).matches();
  }

  /** Label and field selectors go to the API server as they are; only line breaks and control characters are out. */
  static boolean selector(String s) {
    if (s == null) {
      return true;
    }
    if (s.length() > 1024) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (Character.isISOControl(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
