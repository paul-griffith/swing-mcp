package io.github.paul_griffith.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgentConfig#parse(String)}: defaults, overrides, and malformed input. */
class AgentConfigTest {

  @Test
  void nullArgsYieldDefaults() {
    AgentConfig config = AgentConfig.parse(null);
    assertEquals(AgentConfig.DEFAULT_PORT, config.port());
    assertNull(config.token());
    assertFalse(config.hasToken());
  }

  @Test
  void blankArgsYieldDefaults() {
    assertEquals(AgentConfig.defaults(), AgentConfig.parse("   "));
  }

  @Test
  void parsesBothKeysInAnyOrder() {
    AgentConfig config = AgentConfig.parse("token=s3cret,port=9000");
    assertEquals(9000, config.port());
    assertEquals("s3cret", config.token());
    assertTrue(config.hasToken());
  }

  @Test
  void partialArgsKeepDefaultsForTheRest() {
    AgentConfig config = AgentConfig.parse("port=1234");
    assertEquals(1234, config.port());
    assertNull(config.token());
  }

  @Test
  void portZeroIsAllowedForEphemeralBinding() {
    assertEquals(0, AgentConfig.parse("port=0").port());
  }

  @Test
  void blankTokenIsTreatedAsNoToken() {
    AgentConfig config = AgentConfig.parse("token=");
    assertNull(config.token());
    assertFalse(config.hasToken());
  }

  @Test
  void keysAreCaseInsensitiveAndTrimmed() {
    AgentConfig config = AgentConfig.parse(" PORT = 8080 , Token = abc ");
    assertEquals(8080, config.port());
    assertEquals("abc", config.token());
  }

  @Test
  void unknownKeysAreIgnored() {
    AgentConfig config = AgentConfig.parse("port=7000,verbose=true");
    assertEquals(7000, config.port());
  }

  @Test
  void bindIsNoLongerConfigurable() {
    // The server is loopback-only; a former bind= key is ignored rather than honored.
    assertEquals(new AgentConfig(7000, null), AgentConfig.parse("port=7000,bind=0.0.0.0"));
  }

  @Test
  void nonNumericPortIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("port=abc"));
  }

  @Test
  void outOfRangePortIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("port=70000"));
    assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("port=-1"));
  }
}
