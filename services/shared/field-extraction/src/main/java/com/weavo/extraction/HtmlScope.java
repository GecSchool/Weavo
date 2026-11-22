package com.weavo.extraction;

import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/** Parsed document or one list item used as the scope for a relative field selector. */
public final class HtmlScope {
  private final Element root;

  private HtmlScope(Element root) {
    this.root = root;
  }

  public static HtmlScope parse(String html) {
    return parse(html, "");
  }

  public static HtmlScope parse(String html, String baseUrl) {
    return new HtmlScope(Jsoup.parse(Objects.requireNonNull(html, "html"), baseUrl));
  }

  public List<HtmlScope> select(String selector) {
    // Element.querySelectorAll searches descendants without selecting the context element itself.
    return root.select(selector).stream()
        .filter(element -> element != root)
        .map(HtmlScope::new)
        .toList();
  }

  /** Reselects one indexed item from the document's complete CSS matches. */
  public HtmlScope selectItem(String selector, int itemIndex) {
    List<HtmlScope> items = select(selector);
    if (itemIndex < 0 || itemIndex >= items.size()) {
      throw new IllegalArgumentException("Snapshot item index has no matching element");
    }
    return items.get(itemIndex);
  }

  /** Compares a row's text independently of its presentation attributes. */
  public boolean hasSameText(HtmlScope other) {
    return root.text().equals(other.root.text());
  }

  /** Returns a unique link's resolved address, or null if the rule cannot identify it. */
  public String uniqueLink(String selector, String attribute) {
    var matches = select(selector);
    if (matches.size() != 1) {
      return null;
    }
    String value = matches.getFirst().attribute(attribute);
    if (value == null || value.isBlank()) {
      return null;
    }
    String url = matches.getFirst().root.absUrl(attribute);
    return url.isEmpty() ? null : url;
  }

  /** Checks browser markup using the same HTML parser and element context as replay. */
  public boolean matchesMarkup(String markup) {
    var fragment = Parser.parseFragment(markup, root.parent(), "");
    if (fragment.size() != 1 || !(fragment.getFirst() instanceof Element element)) {
      return false;
    }
    var output = root.ownerDocument().outputSettings().clone().prettyPrint(false);
    root.ownerDocument().outputSettings(output);
    element.ownerDocument().outputSettings(output);
    return root.outerHtml().equals(element.outerHtml());
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
