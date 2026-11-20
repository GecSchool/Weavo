package com.weavo.extraction;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Runs the Java body attached to an approved field connector. Reuse per service instance. */
public final class JavaFieldParser {
  private static final int MAX_CACHED_BODIES = 128;
  private final ParserCompiler compiler = new ParserCompiler();
  private final LinkedHashMap<String, ParserCompiler.Compilation> compiledBodies =
      new LinkedHashMap<>(16, 0.75f, true);

  public Result parse(String code, List<String> rawValues) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(rawValues, "rawValues");
    var compiled = compilationFor(code);
    if (compiled.method() == null) {
      return new Result(null, new Failure(Stage.COMPILATION, compiled.diagnostics(), null));
    }
    try {
      // The parser may reorder input without mutating the caller's extraction result.
      var value = (String) compiled.method().invoke(null, new ArrayList<>(rawValues));
      return new Result(value, null);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      // The worker lifecycle controls cancellation. A parser can throw an interruption on its own.
      if (cause instanceof Error error && !(error instanceof AssertionError)) {
        throw error;
      }
      return new Result(null, new Failure(Stage.EXECUTION, List.of(), cause.getClass().getName()));
    } catch (IllegalAccessException exception) {
      throw new IllegalStateException("Compiled parser method is inaccessible", exception);
    }
  }

  private synchronized ParserCompiler.Compilation compilationFor(String code) {
    var cached = compiledBodies.get(code);
    if (cached != null) {
      return cached;
    }
    var compiled = compiler.compile(code);
    compiledBodies.put(code, compiled);
    if (compiledBodies.size() > MAX_CACHED_BODIES) {
      compiledBodies.pollFirstEntry();
    }
    return compiled;
  }

  public enum Stage {
    COMPILATION,
    EXECUTION
  }

  public record Diagnostic(String code, long line, long column) {}

  public record Failure(Stage stage, List<Diagnostic> diagnostics, String exceptionType) {}

  public record Result(String value, Failure failure) {}
}
