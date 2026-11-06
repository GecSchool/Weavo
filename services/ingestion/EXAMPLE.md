# ingestion 예시 코드

추출 결과의 필수 필드 누락 확인 예시입니다. 파싱, S3 조회 및 DB 저장은 구현하지 않았습니다.

- `src/test/java/com/weavo/ingestion/example/RequiredFieldValidator.java`: 예시 로직
- `src/test/java/com/weavo/ingestion/example/RequiredFieldValidatorTest.java`: 해당 로직의 단위 테스트

예시는 학습용으로 테스트 소스에만 있어 애플리케이션이 실행하거나 배포하지 않습니다. 실제 기능은 확정된 명세를 바탕으로 `src/main/java`에 기능별 패키지를 만들어 구현합니다. 코드가 커질 때 Controller, Service, Repository 등을 필요한 책임에 따라 나눕니다.

```bash
./gradlew test spotlessCheck
```
