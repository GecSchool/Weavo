package com.weavo.parser;

import com.weavo.extraction.JavaFieldParser;
import java.io.OutputStream;
import java.io.PrintStream;
import tools.jackson.databind.json.JsonMapper;

/** Container entry point. Its standard input contains one parser body and its own values. */
public final class ParserChild {
  private ParserChild() {}

  public static void main(String[] args) {
    var mapper = JsonMapper.shared();
    var input = mapper.readTree(System.in);
    var values = new java.util.ArrayList<String>();
    input.path("values").forEach(value -> values.add(value.asText()));
    var output = System.out;
    try (var discard = new PrintStream(OutputStream.nullOutputStream())) {
      System.setOut(discard);
      System.setErr(discard);
      var result = new JavaFieldParser().parse(input.path("code").asText(), values);
      var reply = mapper.createObjectNode();
      if (result.failure() == null) {
        reply.put("status", "SUCCEEDED");
        reply.put("value", result.value());
      } else {
        reply.put("status", "FAILED");
        reply.put(
            "failure",
            result.failure().stage() == JavaFieldParser.Stage.COMPILATION
                ? "COMPILATION_FAILED"
                : "EXECUTION_FAILED");
      }
      output.print(mapper.writeValueAsString(reply));
    }
  }
}
