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

`RequestCoordinator`는 `robots.txt`, 리다이렉트, Chromium 하위 요청을 포함해 실제 HTTP 요청마다 호출됩니다. Analysis와 Crawler는 `WEAVO_SITE_REDIS_URL`과 `WEAVO_SITE_DB_URL`, `WEAVO_SITE_DB_USER`, `WEAVO_SITE_DB_PASSWORD`를 같은 저장소로 연결합니다. 로컬 Redis 기본 주소는 `redis://127.0.0.1:16379`이며 PostgreSQL 기본 주소는 `jdbc:postgresql://127.0.0.1:15432/weavo`입니다.

PostgreSQL에 정규화한 호스트별 미종료 요청을 먼저 기록하고 Redis 임대를 얻은 뒤 전송합니다. Redis 키를 잃어도 PostgreSQL의 기존 요청 기록이 남아 있으면 다시 진입하지 못합니다. PostgreSQL에서 새 소유권을 확보한 경우에는 만료된 Redis 임대를 교체할 수 있습니다. 유효한 임대나 요청 간격을 위한 대기 상태가 남아 있으면 유지합니다. Redis 임대를 재확인할 수 없으면 진행 중인 전송을 취소하며, 작업과 페이지의 제한 시간이 Redis 응답보다 먼저 끝나면 해당 기한으로 분류합니다.

조정기는 종료를 시작하면 신규 요청을 멈추고, 진행 중인 전송의 종료 콜백과 반납 재시도를 계속 처리합니다. 서비스의 정상 종료는 반납을 최대 15초 기다립니다. 반납이 확인되면 같은 호스트는 1초 후에 다시 열립니다. 프로세스가 종료 확인 전에 강제 종료된 경우에는 운영자가 이전 전송의 종료를 확인한 뒤 남은 소유권을 정리합니다.

로컬 신규 볼륨은 `infra/local/postgres/02-site-request-leases.sql`을 자동 적용합니다. 기존 로컬 볼륨과 운영 환경은 동일 SQL을 사이트 접근용 PostgreSQL에 먼저 적용하고, 두 서비스가 스키마의 호스트 요청 레코드를 읽고 쓸 수 있게 설정합니다. SQL 적용 과정은 기존 볼륨을 유지한 상태에서 진행할 수 있습니다.

## 검증

저장소 루트에서 실행합니다.

```sh
./services/analysis/gradlew -p services/shared/site-access spotlessCheck test
./services/analysis/gradlew -p services/analysis installChromium
./services/analysis/gradlew -p services/shared/site-access browserTest
./services/analysis/gradlew -p services/shared/site-access redisTest
```

Redis 통합 테스트는 전용 Redis와 위 스키마를 적용한 테스트 PostgreSQL을 사용합니다. `WEAVO_SITE_TEST_REDIS_URL`과 `WEAVO_SITE_TEST_DB_URL`, `WEAVO_SITE_TEST_DB_USER`, `WEAVO_SITE_TEST_DB_PASSWORD`를 설정합니다. 같은 설정으로 `browserTest`를 실행하면 실제 Redis와 PostgreSQL을 거치는 브라우저 테스트도 실행합니다.
