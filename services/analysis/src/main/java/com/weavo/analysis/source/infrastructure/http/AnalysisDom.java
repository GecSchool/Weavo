package com.weavo.analysis.source.infrastructure.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Limits the DOM sent as evidence while preserving the browser's actual extraction tree. */
final class AnalysisDom {
  private static final int MAX_CHARS = 65_536;
  private static final int EXAMPLE_ROWS = 3;
  private static final String CONTENT = "main, article, section, [role=main]";
  private static final String PAGE_CONTROLS =
      "[rel=next], [rel=prev], [class~=next], [class~=pagination], [aria-label*=pagination]";

  private AnalysisDom() {}

  static String compact(String html, boolean listScreen) {
    var document = Jsoup.parse(html);
    document.select("head, script, style, template, svg, link, meta, input[type=hidden]").remove();
    for (var region : document.select("header, footer, [role=banner], [role=contentinfo]")) {
      if (region.closest(CONTENT) == null && region.selectFirst(PAGE_CONTROLS) == null) {
        region.remove();
      }
    }
    document.outputSettings().prettyPrint(false);
    var evidence = document.body().html();
    if (evidence.length() > MAX_CHARS && listScreen) {
      sampleRepeatedRows(document.body());
      evidence = document.body().html();
    }
    return evidence.length() <= MAX_CHARS ? evidence : evidence.substring(0, MAX_CHARS);
  }

  private static void sampleRepeatedRows(Element body) {
    for (var parent : body.select("main, section, div, ul, ol, tbody, [role=list]")) {
      if (parent.closest("nav, [role=navigation]") != null) {
        continue;
      }
      Map<String, List<Element>> groups = new LinkedHashMap<>();
      for (var row : parent.children()) {
        if (row.is("li, article, tr, [role=listitem]") && row.selectFirst(PAGE_CONTROLS) == null) {
          String signature = row.tagName() + " " + row.className();
          groups.computeIfAbsent(signature, key -> new ArrayList<>()).add(row);
        }
      }
      for (var group : groups.values()) {
        for (int index = EXAMPLE_ROWS; index < group.size(); index++) {
          group.get(index).remove();
        }
      }
    }
  }
}
