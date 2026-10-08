package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Wiring test for {@link LoopbackSecurityFilter}: proves an allowed request flows down the chain
 * and a rejected one is short-circuited with the policy's status code and never reaches it.
 *
 * <p>Uses lightweight {@link Proxy}-backed servlet request/response stubs so it stays headless and
 * needs no servlet container or mocking dependency.
 */
class LoopbackSecurityFilterTest {

  private static final int PORT = 8765;

  @Test
  void allowedRequestIsPassedDownTheChain() throws Exception {
    LoopbackSecurityFilter filter = new LoopbackSecurityFilter(new SecurityPolicy(PORT, null));
    AtomicInteger status = new AtomicInteger(0);
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        request(Map.of("Host", "localhost:8765")),
        response(status, new StringWriter()),
        chain(chainCalled));

    assertTrue(chainCalled.get(), "an allowed request must reach the servlet chain");
    assertEquals(0, status.get(), "an allowed request must not set an error status");
  }

  @Test
  void spoofedOriginIsRejectedWithoutReachingTheChain() throws Exception {
    LoopbackSecurityFilter filter = new LoopbackSecurityFilter(new SecurityPolicy(PORT, null));
    AtomicInteger status = new AtomicInteger(0);
    AtomicBoolean chainCalled = new AtomicBoolean(false);
    StringWriter body = new StringWriter();

    filter.doFilter(
        request(Map.of("Host", "localhost:8765", "Origin", "http://evil.example")),
        response(status, body),
        chain(chainCalled));

    assertEquals(403, status.get());
    assertFalse(chainCalled.get(), "a rejected request must not reach the servlet chain");
    assertTrue(
        body.toString().contains("swing-mcp"), "a rejection should write an explanatory body");
  }

  @Test
  void missingTokenIsRejectedWith401() throws Exception {
    LoopbackSecurityFilter filter = new LoopbackSecurityFilter(new SecurityPolicy(PORT, "s3cret"));
    AtomicInteger status = new AtomicInteger(0);
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        request(Map.of("Host", "localhost:8765")),
        response(status, new StringWriter()),
        chain(chainCalled));

    assertEquals(401, status.get());
    assertFalse(chainCalled.get());
  }

  // ------------------------------------------------------------------ proxy-backed stubs

  private static HttpServletRequest request(Map<String, String> headers) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> {
              if ("getHeader".equals(method.getName()) && args != null && args.length == 1) {
                return headers.get((String) args[0]);
              }
              return defaultReturn(method.getReturnType());
            });
  }

  private static HttpServletResponse response(AtomicInteger status, StringWriter body) {
    PrintWriter writer = new PrintWriter(body);
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            HttpServletResponse.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "setStatus" -> status.set((int) args[0]);
                case "getWriter" -> {
                  return writer;
                }
                default -> {
                  // no-op (setContentType, etc.)
                }
              }
              return defaultReturn(method.getReturnType());
            });
  }

  private static FilterChain chain(AtomicBoolean called) {
    return new FilterChain() {
      @Override
      public void doFilter(ServletRequest request, ServletResponse response) {
        called.set(true);
      }
    };
  }

  private static Object defaultReturn(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
