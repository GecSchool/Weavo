# Spring 서비스 구조 및 검토

- 기능별 패키지를 먼저 구분하고 필요한 경우에만 `api`(HTTP·DTO), `application`(유스케이스), `domain`(업무 규칙), `infrastructure`(DB·외부 연동)를 둔다. 각 서비스 README의 예시를 따른다.
- Controller에는 HTTP 경계 책임을 두고 업무 처리는 서비스에 둔다. JPA Entity를 API 요청·응답으로 직접 사용하지 않는다. 입력 경계는 Bean Validation, 상태·소유권 규칙은 서비스에서 확인한다.
- 의존성은 생성자 주입한다. Entity에 Lombok `@Data`나 무분별한 public setter를 쓰지 않고, Optional을 Entity·DTO 필드나 메서드 매개변수로 쓰지 않는다.
- 외부 HTTP 호출을 불필요하게 DB 쓰기 트랜잭션 안에 두지 않는다. 실패를 빈 결과나 정상 결과로 감추지 않고 예외의 원인을 보존한다.
- 포맷은 Spotless와 google-java-format을 따른다. 수정한 서비스에서 `./gradlew spotlessCheck`와 적절한 `./gradlew test`를 실행한다.
- 순수 로직은 단위 테스트, 웹·JPA 경계는 슬라이스 테스트, 실제 DB 계약은 PostgreSQL 통합 테스트를 선택한다. 테스트는 구현 호출보다 결과·상태 변화를 검증한다.
