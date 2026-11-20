package com.weavo.analysis.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class FieldConnectorTest {
  private static FieldConnector.Extract text() {
    return new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null);
  }

  private static FieldConnector.DetailNavigation detail(
      FieldConnector.Target target, FieldConnector.ReturnType returnType, String closeSelector) {
    return new FieldConnector.DetailNavigation(
        new FieldConnector.DetailAccess(FieldConnector.DetailAccessMethod.BROWSER, ".body"),
        new FieldConnector.DetailOpen(FieldConnector.OpenType.CLICK, ".open", null, target),
        new FieldConnector.DetailReturn(returnType, ".list", closeSelector));
  }

  @Test
  void validatesLocationsAndAbsentField() {
    assertThat(new FieldConnector("field", FieldConnector.Location.ABSENT, null, null, null, null))
        .isNotNull();
    assertThatThrownBy(() ->
            new FieldConnector("field", FieldConnector.Location.ABSENT, ".title", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new FieldConnector(
                "field", FieldConnector.Location.DETAIL, ".body", text(), null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new FieldConnector(
                "field",
                FieldConnector.Location.LIST,
                ".title",
                text(),
                null,
                detail(
                    FieldConnector.Target.CURRENT_TAB,
                    FieldConnector.ReturnType.HISTORY_BACK,
                    null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldConnector.Extract(FieldConnector.ExtractType.ATTRIBUTE, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void extractionRequiresTheCorrectAttributeForItsType() {
    assertThat(new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null)).isNotNull();
    assertThat(new FieldConnector.Extract(FieldConnector.ExtractType.ATTRIBUTE, "href"))
        .isNotNull();
    assertThatThrownBy(() -> new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, "href"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldConnector.Extract(FieldConnector.ExtractType.ATTRIBUTE, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresMatchingReturnStrategyAndRequiredSelectors() {
    assertThat(
            detail(FieldConnector.Target.CURRENT_TAB, FieldConnector.ReturnType.HISTORY_BACK, null))
        .isNotNull();
    assertThat(detail(FieldConnector.Target.NEW_TAB, FieldConnector.ReturnType.CLOSE_TAB, null))
        .isNotNull();
    assertThat(detail(FieldConnector.Target.MODAL, FieldConnector.ReturnType.CLOSE, ".close"))
        .isNotNull();
    assertThatThrownBy(() ->
            detail(FieldConnector.Target.NEW_TAB, FieldConnector.ReturnType.HISTORY_BACK, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> detail(FieldConnector.Target.MODAL, FieldConnector.ReturnType.CLOSE, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new FieldConnector.DetailOpen(
                FieldConnector.OpenType.LINK, ".link", null, FieldConnector.Target.CURRENT_TAB))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mapsTheReservedReturnPropertyWithSnakeCase() {
    String json =
        """
        {
          "field_id":"ba4f9e1e-ae8e-4a3c-9261-dca457848148",
          "location":"DETAIL",
          "selector":".article-body",
          "extract":{"type":"TEXT","attribute":null},
          "parser":null,
          "detail_navigation":{
            "access":{"method":"BROWSER","wait_selector":".article-body"},
            "open":{"type":"CLICK","selector":".open","target":"NEW_TAB"},
            "return":{"type":"CLOSE_TAB","wait_selector":".list"}
          }
        }
        """;
    var mapper =
        JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    FieldConnector connector = mapper.readValue(json, FieldConnector.class);
    String encoded = mapper.writeValueAsString(connector);

    assertThat(connector.detailNavigation().returnRule().type())
        .isEqualTo(FieldConnector.ReturnType.CLOSE_TAB);
    assertThat(encoded).contains("\"return\":").doesNotContain("return_rule");
    assertThat(mapper.readTree(encoded).get("detail_navigation").get("open").has("url_attribute"))
        .isFalse();
    assertThat(mapper.readTree(encoded).get("detail_navigation").get("return").has("selector"))
        .isFalse();
    assertThat(mapper.readValue(encoded, FieldConnector.class)).isEqualTo(connector);
  }
}
