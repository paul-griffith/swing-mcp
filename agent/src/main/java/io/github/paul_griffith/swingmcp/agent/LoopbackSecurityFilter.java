package io.github.paul_griffith.swingmcp.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter enforcing {@link SecurityPolicy} in front of the MCP transport servlet. A rejected
 * request is answered with the policy's status code and never reaches the transport; an allowed
 * request is passed down the chain unchanged.
 *
 * <p>{@link HttpFilter} already routes the raw {@code doFilter} to the typed overload below, so
 * this is the single implementation point.
 */
final class LoopbackSecurityFilter extends HttpFilter {

  private final SecurityPolicy policy;

  LoopbackSecurityFilter(SecurityPolicy policy) {
    this.policy = policy;
  }

  @Override
  protected void doFilter(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    SecurityPolicy.Decision decision =
        policy.evaluate(
            request.getHeader("Host"),
            request.getHeader("Origin"),
            request.getHeader("Authorization"));
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(decision.status());
    response.setContentType("text/plain; charset=utf-8");
    response.getWriter().write("swing-mcp: " + decision.reason());
  }
}
