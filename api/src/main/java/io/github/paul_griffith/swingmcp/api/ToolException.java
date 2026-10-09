package io.github.paul_griffith.swingmcp.api;

import java.util.regex.Pattern;

/**
 * Fails a tool call with a structured error, {@code {error: code, message, hint?}}, the shape every
 * built-in tool uses, so a client can branch on {@link #code()} instead of parsing the message.
 *
 * <p>Reuse a built-in code where one fits ({@code invalid_argument}, {@code component_not_found})
 * and otherwise pick a specific one ({@code not_a_status_indicator}). Give a {@link #hint()} when
 * there is a concrete next step for the caller.
 */
public class ToolException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]*");

  private final String code;
  private final String hint;

  /**
   * @param code a lower_snake_case error code
   * @param message what went wrong
   * @throws IllegalArgumentException if {@code code} is not lower_snake_case
   */
  public ToolException(String code, String message) {
    this(code, message, null);
  }

  /**
   * @param code a lower_snake_case error code
   * @param message what went wrong
   * @param hint the recovery step for the caller, or {@code null}
   * @throws IllegalArgumentException if {@code code} is not lower_snake_case
   */
  public ToolException(String code, String message, String hint) {
    super(message);
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("error code must be lower_snake_case, got: " + code);
    }
    this.code = code;
    this.hint = hint;
  }

  /** The error code. */
  public String code() {
    return code;
  }

  /** The recovery hint, or {@code null}. */
  public String hint() {
    return hint;
  }
}
