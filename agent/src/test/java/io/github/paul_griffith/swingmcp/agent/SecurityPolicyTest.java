package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paul_griffith.swingmcp.agent.SecurityPolicy.Decision;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SecurityPolicy}: Host / Origin / bearer-token enforcement. */
class SecurityPolicyTest {

  private static final int PORT = 8765;

  private static SecurityPolicy open() {
    return new SecurityPolicy(PORT, null);
  }

  private static SecurityPolicy withToken(String token) {
    return new SecurityPolicy(PORT, token);
  }

  // ------------------------------------------------------------------ Host

  @Test
  void loopbackHostsOnTheConfiguredPortAreAllowed() {
    SecurityPolicy policy = open();
    assertTrue(policy.evaluate("127.0.0.1:8765", null, null).allowed());
    assertTrue(policy.evaluate("localhost:8765", null, null).allowed());
    assertTrue(policy.evaluate("[::1]:8765", null, null).allowed());
  }

  @Test
  void missingHostIsRejected() {
    Decision decision = open().evaluate(null, null, null);
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  @Test
  void nonLoopbackHostIsRejected() {
    Decision decision = open().evaluate("evil.example:8765", null, null);
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  @Test
  void wrongHostPortIsRejected() {
    Decision decision = open().evaluate("127.0.0.1:9999", null, null);
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  @Test
  void nonLoopbackHostNameIsRejected() {
    Decision decision = open().evaluate("my-host.local:8765", null, null);
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  @Test
  void anyIpLiteralInTheLoopbackBlockIsAllowed() {
    SecurityPolicy policy = open();
    assertTrue(policy.evaluate("127.0.0.2:8765", null, null).allowed());
    assertTrue(policy.evaluate("127.255.255.254:8765", null, null).allowed());
  }

  @Test
  void loopbackPrefixedHostnameIsRejectedAsHost() {
    // DNS-rebinding guard: `127.evil.com` is a resolvable hostname, not a 127.x.y.z IP literal.
    SecurityPolicy policy = open();
    assertFalse(policy.evaluate("127.evil.com:8765", null, null).allowed());
    assertFalse(policy.evaluate("127.0.0.1.evil.com:8765", null, null).allowed());
  }

  @Test
  void loopbackPrefixedHostnameIsRejectedAsOrigin() {
    SecurityPolicy policy = open();
    assertFalse(policy.evaluate("localhost:8765", "http://127.evil.com", null).allowed());
    assertFalse(policy.evaluate("localhost:8765", "http://127.0.0.1.evil.com", null).allowed());
  }

  @Test
  void malformedIpv4OctetsAreRejected() {
    SecurityPolicy policy = open();
    assertFalse(policy.evaluate("127.0.0.256:8765", null, null).allowed());
    assertFalse(policy.evaluate("127.0.0:8765", null, null).allowed());
  }

  // ------------------------------------------------------------------ Origin

  @Test
  void missingOriginIsAllowed() {
    assertTrue(open().evaluate("localhost:8765", null, null).allowed());
  }

  @Test
  void loopbackOriginIsAllowed() {
    assertTrue(open().evaluate("localhost:8765", "http://localhost:8765", null).allowed());
    assertTrue(open().evaluate("localhost:8765", "http://127.0.0.1:5173", null).allowed());
  }

  @Test
  void opaqueNullOriginIsAllowed() {
    assertTrue(open().evaluate("localhost:8765", "null", null).allowed());
  }

  @Test
  void remoteOriginIsRejectedForDnsRebindingProtection() {
    Decision decision = open().evaluate("localhost:8765", "http://evil.example", null);
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  // ------------------------------------------------------------------ Authorization (token)

  @Test
  void missingAuthorizationIsUnauthorizedWhenTokenConfigured() {
    Decision decision = withToken("s3cret").evaluate("localhost:8765", null, null);
    assertFalse(decision.allowed());
    assertEquals(401, decision.status());
  }

  @Test
  void nonBearerAuthorizationIsUnauthorized() {
    Decision decision = withToken("s3cret").evaluate("localhost:8765", null, "Basic Zm9vOmJhcg==");
    assertFalse(decision.allowed());
    assertEquals(401, decision.status());
  }

  @Test
  void wrongBearerTokenIsForbidden() {
    Decision decision = withToken("s3cret").evaluate("localhost:8765", null, "Bearer nope");
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }

  @Test
  void correctBearerTokenIsAllowedCaseInsensitiveScheme() {
    assertTrue(withToken("s3cret").evaluate("localhost:8765", null, "Bearer s3cret").allowed());
    assertTrue(withToken("s3cret").evaluate("localhost:8765", null, "bearer s3cret").allowed());
  }

  @Test
  void hostIsCheckedBeforeToken() {
    // A remote Host is rejected (403) even when a valid token is presented.
    Decision decision = withToken("s3cret").evaluate("evil.example:8765", null, "Bearer s3cret");
    assertFalse(decision.allowed());
    assertEquals(403, decision.status());
  }
}
