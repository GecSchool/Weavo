# 공통 필드 추출·파싱·타입 변환

Analysis에서 확보한 DOM, Crawler의 임시 추출에 쓰는 DOM, Ingestion에서 읽은 저장 HTML에 같은 CSS 추출 규칙을 적용할 수 있는 Java 모듈입니다. 목록에서는 `item_selector`로 찾은 항목에서 필드를 읽고, 상세에서는 상세 DOM을 기준으로 읽습니다.

```java
var processor = new FieldValueProcessor(new JavaFieldParser());
var document = HtmlScope.parse(html);
var item = document.select("article.notice").getFirst();
var extracted = FieldExtractor.extractText(item, ".amount");
String code = """
    return rawValues.getFirst().replace(",", "").replace("원", "");
    """;
var result = processor.process(extracted, code, FieldValueConverter.Type.NUMBER, true);
if (result.status() == FieldExtractor.Status.SUCCESS) {
  // result.value()에는 타입 검증을 통과한 값이 들어 있습니다.
}
```

지정 속성 원문은 `extractAttribute(scope, selector, attribute)`로 읽습니다. 여러 요소가 매칭되면 DOM 순서의 문자열 목록을 보존합니다. `FieldValueProcessor`는 추출 상태를 확인한 뒤 Java 파서와 타입 변환을 순서대로 호출합니다.

| 상황 | 반환 |
|---|---|
| 원래 없는 필드 | `absent()` → `ABSENT`, `matchCount=null` |
| DOM을 확보하지 못했거나 선택자가 잘못됨 | `FAILED`, `matchCount=null` |
| 선택자가 실행됐고 일치 요소가 없음 | `FAILED`, `matchCount=0` |
| 요소는 매칭됐지만 지정 속성이 일부 없음 | `FAILED`, 실제 매칭 수와 확보한 원문 |
| 요소 값이 빈 문자열 | `SUCCESS`, 원문 목록에 빈 문자열 유지 |
| 파서 설정 없이 원문이 여러 개 매칭됨 | `FAILED`, `PARSER_INPUT`, `SINGLE_VALUE_REQUIRED` |
| Java 코드 컴파일 실패 | `FAILED`, 컴파일 오류 코드와 본문 기준 줄·열 |
| Java 파서 실행 예외 | `FAILED`, 예외 타입 |
| nullable 필드의 실제 null | 변환 성공, 값 null |
| 숫자·날짜 등 스키마 타입 변환 실패 | 값 null, `TYPE_CONVERSION_FAILED` |

변환 실패와 추출 실패가 있는 레코드는 호출 서비스가 적재에서 제외합니다. 실패 원문은 필요한 경우 기존 sidecar 정책에 따라 분리합니다.

문자열과 속성값은 HTML 엔티티를 해석한 뒤 공백과 순서를 유지합니다. 숫자는 JSON 숫자 구문을 받아 `BigDecimal`로 변환하고 값이 같은 소수의 자릿수를 통일합니다. 불리언 입력은 `true` 또는 `false`입니다. 날짜는 `YYYY-MM-DD`로 검증하고 일시는 오프셋이 있는 입력을 UTC로 변환합니다. 일시는 나노초까지 지원합니다. URL은 호스트가 있는 절대 HTTP(S) 형식을 확인하고 원문을 반환합니다.

## 파서 실행

`parser.code`에는 메서드 본문을 넣습니다. 입력 `rawValues`는 DOM 순서의 문자열 목록이며 반환값은 문자열 또는 실제 `null`입니다. 파서를 생략하면 원문이 정확히 한 개일 때 그 문자열을 사용합니다. 원문이 여러 개일 때는 단일 값을 만드는 파싱 규칙을 지정합니다. 추출에 실패한 필드는 실패 상태를 유지합니다. 파서 본문의 실행 예외와 단언 실패는 해당 필드의 실패로 반환합니다. 작업 중단은 워커의 실행 제어에서 관리합니다.

현재 구현과 테스트는 Java 21에서 동작합니다. `JavaFieldParser`는 서비스 인스턴스에서 재사용할 수 있습니다. 컴파일된 메서드는 인스턴스 안에서 최대 128개 보관합니다. 현재 구현은 서비스 JVM에서 실행합니다. 목표 구조에서는 공통 모듈이 CSS 추출과 타입 변환을 맡고, 컴파일·캐시·실행을 별도 실행기로 이동합니다. 호출 계약은 [메시지 명세 4.3·12.1](../../../docs/service-message-contracts.md)을 따릅니다. 결과 진단에는 파서 본문과 추출 원문을 포함하지 않습니다.

## 서비스에 연결

해당 기능의 구현 단계에서 서비스의 `settings.gradle`에 `includeBuild('../shared/field-extraction')`을 추가하고, `build.gradle`의 의존성에 `implementation 'com.weavo:field-extraction'`을 등록합니다.

## 테스트

저장소 루트에서 실행합니다.

```bash
./services/analysis/gradlew -p services/shared/field-extraction spotlessCheck test
./services/analysis/gradlew -p services/analysis installChromium
./services/analysis/gradlew -p services/shared/field-extraction browserTest
```

`browserTest`는 고정 HTML에서 원본 마크업과 Chromium이 저장한 DOM을 비교합니다. 테스트에 포함한 자식·자손·그룹·위치·속성 선택자에서 브라우저가 직접 읽은 결과도 대조합니다.
