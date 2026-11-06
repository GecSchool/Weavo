# control 예시 코드

출처 상태 목록에서 준비 중인 개수를 계산하는 예시입니다. 실제 상태 이름이나 전이 규칙은 정하지 않습니다.

- `src/test/java/com/weavo/control/example/CollectionStatusSummary.java`: 예시 로직
- `src/test/java/com/weavo/control/example/CollectionStatusSummaryTest.java`: 해당 로직의 단위 테스트

예시는 학습용으로 테스트 소스에만 있어 애플리케이션이 실행하거나 배포하지 않습니다. 실제 기능은 확정된 명세를 바탕으로 `src/main/java`에 기능별 패키지를 만들어 구현합니다. 코드가 커질 때 Controller, Service, Repository 등을 필요한 책임에 따라 나눕니다.

```bash
./gradlew test spotlessCheck
```

## 기능별 패키지 예시

`src/test/java/com/weavo/control/example/project/`에는 프로젝트 조회를 가정한 예시도 있습니다.

```text
project/
├── ProjectSummaryController.java  # HTTP 응답 처리
├── ProjectSummaryService.java     # 조회 흐름
├── ProjectSummaryRepository.java  # 저장소에 필요한 동작
├── ProjectSummary.java            # 조회 결과
└── ProjectSummaryServiceTest.java # 저장소 대역을 이용한 단위 테스트
```

컨트롤러는 API로 등록되어 있지 않으며, 저장소 구현체도 없습니다. 구조 설명을 위한 예시이므로 실제 API 경로나 ID 형식으로 취급하지 마세요. 실제 구현에서는 인증·권한과 명세를 반영하고 DTO와 영속 객체도 책임에 따라 구분합니다.
