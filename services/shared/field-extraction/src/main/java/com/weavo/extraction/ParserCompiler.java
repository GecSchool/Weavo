package com.weavo.extraction;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.weavo.extraction.JavaFieldParser.Diagnostic;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Compiles the contracted Java method body into an in-memory class. */
final class ParserCompiler {
  private static final String CLASS_NAME = "com.weavo.extraction.generated.GeneratedParser";
  private static final URI SOURCE_URI =
      URI.create("memory:///com/weavo/extraction/generated/GeneratedParser.java");
  private static final String HEADER =
      """
      package com.weavo.extraction.generated;

      public final class GeneratedParser {
        public static String parse(java.util.List<String> rawValues) throws Exception {
      """;
  private static final String FOOTER = "\n  }\n}\n";
  private static final long HEADER_LINES = HEADER.lines().count();

  Compilation compile(String body) {
    if (body.isBlank()) {
      return failure("parser.body.required");
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return failure("parser.compiler.unavailable");
    }
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var bytecode = new ByteArrayOutputStream();
    try (var standard =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      // Generated functions reference Java standard classes. No application classpath is required.
      standard.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
      var files =
          new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(
                Location location, String className, Kind kind, FileObject sibling)
                throws IOException {
              if (!CLASS_NAME.equals(className) || kind != Kind.CLASS) {
                throw new IOException("Unexpected parser compiler output");
              }
              return new SimpleJavaFileObject(URI.create("memory:///GeneratedParser.class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                  return bytecode;
                }
              };
            }
          };
      var source =
          new SimpleJavaFileObject(SOURCE_URI, Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return HEADER + body + FOOTER;
            }
          };
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("--release", "21", "-proc:none"),
                  null,
                  List.of(source));
      var unit = task.parse().iterator().next();
      var errors = errors(diagnostics);
      if (!errors.isEmpty()) {
        return new Compilation(null, errors);
      }
      if (!hasOnlyParserBody(task, unit, body.length())) {
        return failure("parser.body.declarations");
      }

      task.generate();
      errors = errors(diagnostics);
      return errors.isEmpty()
          ? new Compilation(loadMethod(bytecode.toByteArray()), List.of())
          : new Compilation(null, errors);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to use the Java parser compiler", exception);
    }
  }

  private static boolean hasOnlyParserBody(
      JavacTask task, CompilationUnitTree unit, int bodyLength) {
    var declarations = unit.getTypeDecls();
    if (!unit.getImports().isEmpty()
        || declarations.size() != 1
        || !(declarations.getFirst() instanceof ClassTree parser)
        || parser.getMembers().size() != 1
        || !(parser.getMembers().getFirst() instanceof MethodTree method)) {
      return false;
    }

    var block = method.getBody();
    var positions = Trees.instance(task).getSourcePositions();
    if (positions.getStartPosition(unit, block) != HEADER.lastIndexOf('{')
        || positions.getEndPosition(unit, block)
            != HEADER.length() + bodyLength + FOOTER.indexOf('}') + 1) {
      return false;
    }
    // A class declaration inside the body would generate a second class file.
    var classes =
        new TreeScanner<Boolean, Void>() {
          @Override
          public Boolean visitClass(ClassTree tree, Void unused) {
            return true;
          }

          @Override
          public Boolean reduce(Boolean left, Boolean right) {
            return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
          }
        };
    return !Boolean.TRUE.equals(classes.scan(block, null));
  }

  private static Method loadMethod(byte[] bytecode) {
    var loader =
        new ClassLoader(ClassLoader.getPlatformClassLoader()) {
          Class<?> defineParser() {
            return defineClass(CLASS_NAME, bytecode, 0, bytecode.length);
          }
        };
    try {
      return loader.defineParser().getMethod("parse", List.class);
    } catch (NoSuchMethodException exception) {
      throw new IllegalStateException("Compiled parser method is missing", exception);
    }
  }

  private static List<Diagnostic> errors(DiagnosticCollector<JavaFileObject> diagnostics) {
    // Compiler messages can contain fragments of the original code. Return only codes and
    // positions.
    return diagnostics.getDiagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
        .map(diagnostic ->
            new Diagnostic(
                diagnostic.getCode(),
                Math.max(1, diagnostic.getLineNumber() - HEADER_LINES),
                Math.max(1, diagnostic.getColumnNumber())))
        .toList();
  }

  private static Compilation failure(String code) {
    return new Compilation(null, List.of(new Diagnostic(code, 1, 1)));
  }

  record Compilation(Method method, List<Diagnostic> diagnostics) {}
}
