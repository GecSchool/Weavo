package com.weavo.analysis.job.infrastructure.messaging;

/** A contract failure whose diagnostics are safe to include in logs. */
public class ContractViolationException extends IllegalArgumentException {
  public ContractViolationException(String message) {
    super(message);
  }

  private ContractViolationException(String message, Throwable cause) {
    super(message, cause);
  }

  static ContractViolationException fromRedactedCause(String message, Throwable cause) {
    // Parser exception messages and nested causes can include raw input.
    var redactedCause = new IllegalArgumentException(cause.getClass().getSimpleName());
    redactedCause.setStackTrace(cause.getStackTrace());
    return new ContractViolationException(message, redactedCause);
  }
}
