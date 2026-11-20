package com.weavo.extraction;

import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Parsed document or one list item used as the scope for a relative field selector. */
public final class HtmlScope {
  private final Element root;

  private HtmlScope(Element root) {
    this.root = root;
  }

  public static HtmlScope parse(String html) {
    return new HtmlScope(Jsoup.parse(Objects.requireNonNull(html, "html")));
  }

  public List<HtmlScope> select(String selector) {
    // Element.querySelectorAll searches descendants without selecting the context element itself.
    return root.select(selector).stream()
        .filter(element -> element != root)
        .map(HtmlScope::new)
        .toList();
  }

  String textContent() {
    var text = new StringBuilder();
    // Follow DOM textContent semantics, preserving raw spaces and omitting comments.
    root.forEachNode(node -> {
      if (node instanceof TextNode textNode) {
        text.append(textNode.getWholeText());
      } else if (node instanceof DataNode dataNode) {
        text.append(dataNode.getWholeData());
      }
    });
    return text.toString();
  }

  String attribute(String name) {
    var attributes = root.attributes();
    return attributes.hasKeyIgnoreCase(name) ? attributes.getIgnoreCase(name) : null;
  }
}
