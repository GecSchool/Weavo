package com.weavo.analysis.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.weavo.siteaccess.rule.Navigation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationTest {
  private static Navigation.Pagination nextLink() {
    return new Navigation.Pagination(
        Navigation.PaginationType.NEXT_LINK, ".next", "href", null, null, null, null, null);
  }

  private static Navigation rule(
      String url, List<Navigation.InitialAction> actions, Navigation.Pagination pagination) {
    return new Navigation(
        url,
        actions,
        new Navigation.ListAccess(Navigation.AccessMethod.BROWSER, ".list"),
        ".item",
        pagination);
  }

  @Test
  void comparesCompleteRulesWithOnlyConservativeUrlNormalization() {
    Navigation first = rule("HTTPS://Example.COM:443/a%2Fb?x=1&y=2#first", List.of(), nextLink());
    Navigation same = rule("https://example.com/a%2Fb?x=1&y=2#second", List.of(), nextLink());
    Navigation queryOrder = rule("https://example.com/a%2Fb?y=2&x=1", List.of(), nextLink());
    Navigation decodedPath = rule("https://example.com/a/b?x=1&y=2", List.of(), nextLink());
    Navigation otherPort = rule("https://example.com:444/a%2Fb?x=1&y=2", List.of(), nextLink());

    assertThat(first.hasSameSourceAs(same)).isTrue();
    assertThat(first.hasSameSourceAs(queryOrder)).isFalse();
    assertThat(first.hasSameSourceAs(decodedPath)).isFalse();
    assertThat(first.hasSameSourceAs(otherPort)).isFalse();
    assertThat(first.hasSameSourceAs(null)).isFalse();
    assertThat(Navigation.normalizedListUrl("HTTP://Example.COM:80/a/../b?"))
        .isEqualTo("http://example.com/a/../b?");
    assertThat(Navigation.normalizedListUrl("https://example.com"))
        .isNotEqualTo(Navigation.normalizedListUrl("https://example.com/"));
  }

  @Test
  void initialActionOrderAndPaginationArePartOfSourceIdentity() {
    var first = new Navigation.InitialAction(Navigation.ActionType.CLICK, ".a", ".list");
    var second = new Navigation.InitialAction(Navigation.ActionType.CLICK, ".b", ".list");
    Navigation left = rule("https://example.com/list", List.of(first, second), nextLink());
    Navigation reordered = rule("https://example.com/list", List.of(second, first), nextLink());
    Navigation otherPage =
        rule(
            "https://example.com/list",
            List.of(first, second),
            new Navigation.Pagination(
                Navigation.PaginationType.NEXT_LINK,
                ".other",
                "href",
                null,
                null,
                null,
                null,
                null));

    assertThat(left.hasSameSourceAs(reordered)).isFalse();
    assertThat(left.hasSameSourceAs(otherPage)).isFalse();
  }

  @Test
  void rejectsInvalidHttpAndPaginationCombinations() {
    var browserOnly =
        new Navigation.Pagination(
            Navigation.PaginationType.CLICK_NEXT, ".next", null, ".list", null, null, null, null);
    var action = new Navigation.InitialAction(Navigation.ActionType.CLICK, ".tab", ".list");
    var http = new Navigation.ListAccess(Navigation.AccessMethod.HTTP, null);

    assertThatThrownBy(
            () -> new Navigation("https://example.com", List.of(), http, ".item", browserOnly))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Navigation("https://example.com", List.of(action), http, ".item", nextLink()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("/relative", List.of(), nextLink()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("file:///etc/passwd", List.of(), nextLink()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Navigation.InitialAction(Navigation.ActionType.CLICK, ".tab", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesUrlTemplateAndScrollRules() {
    var valid =
        new Navigation.Pagination(
            Navigation.PaginationType.URL_TEMPLATE,
            null,
            null,
            null,
            "https://example.com/pages/{value}?a=1",
            1,
            1,
            null);
    assertThat(valid.step()).isEqualTo(1);
    assertThatThrownBy(() ->
            new Navigation.Pagination(
                Navigation.PaginationType.URL_TEMPLATE,
                null,
                null,
                null,
                "https://example.com/{value}/{value}",
                1,
                1,
                null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new Navigation.Pagination(
                Navigation.PaginationType.URL_TEMPLATE,
                null,
                null,
                null,
                "https://example.com/{value}",
                1,
                0,
                null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new Navigation.Pagination(
                Navigation.PaginationType.SCROLL, null, null, ".item", null, null, null, null))
        .isNotNull();
    assertThatThrownBy(() ->
            new Navigation.Pagination(
                Navigation.PaginationType.SCROLL, null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsActionListImmutable() {
    var mutable = new ArrayList<Navigation.InitialAction>();
    mutable.add(new Navigation.InitialAction(Navigation.ActionType.CLICK, ".a", ".list"));
    Navigation navigation = rule("https://example.com", mutable, nextLink());
    mutable.clear();

    assertThat(navigation.initialActions()).hasSize(1);
    assertThatThrownBy(() -> navigation.initialActions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void malformedUrlErrorsDoNotExposeQuerySecrets() {
    assertThatThrownBy(() -> Navigation.normalizedListUrl("https://example.com/?token=SECRET bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SECRET")
        .hasNoCause();
  }
}
