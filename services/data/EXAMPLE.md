# data 예시 코드

저장된 데이터에서 응답할 필드를 선택하는 단순 예시입니다. 실제 API 필터 계약이나 권한 검사는 구현하지 않았습니다.

- `src/test/java/com/weavo/data/example/RecordFieldSelector.java`: 예시 로직
- `src/test/java/com/weavo/data/example/RecordFieldSelectorTest.java`: 해당 로직의 단위 테스트

예시는 학습용으로 테스트 소스에만 있어 애플리케이션이 실행하거나 배포하지 않습니다. 실제 기능은 확정된 명세를 바탕으로 `src/main/java`에 기능별 패키지를 만들어 구현합니다. 코드가 커질 때 Controller, Service, Repository 등을 필요한 책임에 따라 나눕니다.

```bash
./gradlew test spotlessCheck
```
