# 공통 사이트 접근

Analysis와 Crawler에서 `SiteAccess`를 공유합니다. 작업마다 `SiteSession`을 열면 해당 작업의 `robots.txt` 검사 결과를 HTTP 요청과 브라우저 요청에 함께 사용합니다.

```java
var budget = new WorkBudget(
    attempt.workDeadline(), attempt::isCancellationRequested, attempt::requireWorkAllowed);
var sites = siteAccess.open(budget);
var staticPage = sites.fetchHtml(listUrl);

try (var browser = sites.openBrowser(); var tab = browser.newTab()) {
  var rendered = tab.navigate(listUrl, ".item");
  // rendered.url()에서 최종 URL을, rendered.html()에서 렌더링한 DOM을 확인합니다.
}
```

브라우저 세션과 탭은 생성한 스레드에서 사용하고 닫습니다. CSS 선택자를 넘기면 해당 요소가 DOM에 들어올 때까지 작업 제한 시간 안에서 기다립니다. 이미지·미디어·폰트는 기본 차단하며 필요한 유형은 `openBrowser(Set<Resource>)`로 허용할 수 있습니다. 브라우저 네트워크 요청은 공개 데이터를 읽는 GET·HEAD·OPTIONS만 허용합니다.

Chromium은 연결 상태를 온라인으로 보고하며, 공통 전송 계층이 받아서 처리한 요청만 외부에 도달합니다. 같은 출처의 목록 데이터 요청이 차단되면 페이지 접근 결과에 차단을 전달합니다.

`RequestCoordinator`는 실제 HTTP 요청마다 호출됩니다. `robots.txt`, 리다이렉트, Chromium 하위 요청도 각각 허가를 획득하고 해제합니다. 서비스 간 Redis 요청 제한은 이 인터페이스에 연결합니다.

## 검증

저장소 루트에서 실행합니다.

```sh
./services/analysis/gradlew -p services/shared/site-access spotlessCheck test
./services/analysis/gradlew -p services/analysis installChromium
./services/analysis/gradlew -p services/shared/site-access browserTest
```

브라우저 테스트는 로컬 테스트 서버와 설치된 Chromium을 사용합니다. 외부 사이트에 의존하지 않습니다.
