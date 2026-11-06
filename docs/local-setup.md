# 로컬 개발 환경

## 준비

Git, Docker Desktop 및 Docker Compose가 필요합니다. Spring 서비스 개발에는 JDK 21, 프론트엔드에는 Node.js, 분석·크롤러에는 Python이 필요합니다. 각 서비스의 버전과 실행 방법은 아래 표와 서비스 README를 참고하세요.

LocalStack 계정의 유효한 인증 토큰을 준비합니다. [Auth Tokens 페이지](https://app.localstack.cloud/workspace/auth-tokens)에 로그인해 개인 개발용 Developer Token을 확인합니다. Docker Compose로 실행할 때는 이 토큰이 필요합니다. 토큰은 다른 사람에게 전달하거나 Git에 커밋하지 않습니다.

## 실행

저장소 루트에서:

```bash
test -f infra/local/.env || cp infra/local/.env.example infra/local/.env
```

`infra/local/.env`의 `LOCALSTACK_AUTH_TOKEN`을 각자의 값으로 채웁니다. 이 파일은 Git에서 제외됩니다. 이후 Docker Desktop을 켜고 다음을 실행합니다.

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml up -d
docker compose --env-file infra/local/.env -f infra/local/compose.yaml ps
docker compose --env-file infra/local/.env -f infra/local/compose.yaml logs localstack
```

LocalStack READY 초기화가 완료되면 로그에 `Weavo local queues, DLQs and S3 bucket are ready.`가 표시됩니다. 서비스를 내릴 때는 다음을 사용합니다.

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml down
```

PostgreSQL 데이터는 Docker 볼륨에 남습니다. LocalStack의 상태는 기본적으로 종료 후 초기화되며 시작할 때 큐와 버킷이 다시 생성됩니다. `down -v`는 PostgreSQL 데이터도 삭제하므로 초기화가 필요할 때만 사용합니다.

## 로컬 자원

- PostgreSQL: `localhost:15432`, `weavo_auth`, `weavo_control`, `weavo_data` 데이터베이스. Data와 Ingestion이 `weavo_data`를 사용합니다.
- Redis: `localhost:16379`.
- LocalStack: `http://localhost:4566` (호스트에서 실행한 프로그램 기준), 리전 `ap-northeast-2`, 버킷 `weavo-local-artifacts`.
- SQS: `collection-schedule-requests`, `analysis-jobs`, `collection-priority-jobs`, `collection-scheduled-jobs`, `ingestion-jobs`, `control-results`. 각 큐에 대응하는 이름의 `-dlq` 큐도 생성합니다.

정기 수집 신호는 Control이 수신하고, 수동·최초 요청과 정기 요청은 서로 다른 큐에서 Crawler가 수신합니다. Control 결과 큐는 결과를 유형별로 분기합니다. 우선 큐와 정기 큐의 3:1 실행 정책은 Crawler에서 구현해야 하며 큐 생성만으로 동작하지 않습니다.

DLQ는 만들어 두지만 기술 재전달 횟수와 redrive 정책은 아직 확정되지 않아 자동 연결하지 않습니다. EventBridge Scheduler도 Control이 공유 출처별 일정을 생성하는 흐름을 구현할 때 등록합니다. 정기 신호는 우선 큐로 직접 보내지 않습니다.

컨테이너에서 실행하는 서비스는 일반적으로 `localhost` 대신 같은 Compose 네트워크의 `postgres`, `redis`, `localstack`을 사용합니다. SQS 응답의 Queue URL도 소비자의 실행 위치에서 접근되는지 확인해야 합니다. 애플리케이션 서비스의 Docker 구성과 네트워크 연결은 해당 서비스 구현 시 추가합니다.

현재 리소스는 개발용이며 AWS 개발 환경의 리소스와 분리합니다. Java 파싱 코드의 Python·Java 공통 실행 방식은 추가 결정이 필요합니다.

## 애플리케이션 실행

Node.js는 `apps/web/.nvmrc`의 22.23.3, Spring은 Java 21을 사용합니다. Python은 3.12 또는 3.13을 사용합니다. 각 서비스 디렉터리의 README에 설치·실행·테스트 명령이 있습니다. 프론트엔드는 `cd apps/web && nvm install && nvm use && npm ci && npm run dev`로 실행합니다.

로컬 인프라가 실행된 상태에서 Spring 서비스 디렉터리별로 `./gradlew bootRun`을 실행합니다. 상태 확인 주소는 각각 `http://localhost:8081/actuator/health` (Auth), 8082 (Control), 8083 (Data), 8084 (Ingestion)입니다. 포트와 DB 연결은 각 서비스의 `application-local.properties`에서 환경 변수로 변경할 수 있습니다.

Analysis·Crawler는 각각 `.venv`를 만들고 README의 고정 의존성을 설치합니다. `WEAVO_ENV=local WEAVO_AWS_ENDPOINT_URL=http://localhost:4566 python -m weavo_crawler --check` 또는 `python -m weavo_analysis --check`로 큐·버킷 연결을 조회할 수 있습니다. 이 명령은 메시지를 소비하지 않습니다. 배포 환경은 로컬 endpoint와 테스트 자격 증명을 사용하지 않으며 리소스 이름을 환경 변수로 지정합니다.

## 프로젝트 위치와 서비스별 안내

| 경로 | 구성 | 로컬 실행 |
|---|---|---|
| [웹](../apps/web/README.md) | Next.js · TypeScript · TanStack Query | `nvm use` 후 `npm ci && npm run dev` |
| [Auth](../services/auth/README.md) | Spring Boot · Java 21 | `./gradlew bootRun` (8081) |
| [Control](../services/control/README.md) | Spring Boot · Java 21 | `./gradlew bootRun` (8082) |
| [Data](../services/data/README.md) | Spring Boot · Java 21 | `./gradlew bootRun` (8083) |
| [Ingestion](../services/ingestion/README.md) | Spring Boot · Java 21 | `./gradlew bootRun` (8084) |
| [Analysis](../services/analysis/README.md) | Python · Playwright · boto3 | 서비스 README 참고 |
| [Crawler](../services/crawler/README.md) | Python · Playwright · boto3 | 서비스 README 참고 |

`infra/local/`에는 로컬 PostgreSQL·Redis·LocalStack 설정이 있고, `services/`와 `apps/`에는 각각 백엔드와 프론트엔드 프로젝트가 있습니다. 현재 Python의 `--check`는 연결 확인만 하며, 실제 업무 기능과 메시지 소비는 구현 전입니다.

## DB 스키마 변경 관리

PostgreSQL 인스턴스 하나에 `weavo_auth`, `weavo_control`, `weavo_data`가 각각 존재합니다. 각 DB의 스키마 변경은 Auth, Control, Ingestion의 `src/main/resources/db/migration/`에 버전별 Flyway SQL로 기록합니다. Data는 `weavo_data`를 Ingestion과 공유하며 마이그레이션을 실행하지 않습니다.

1. 테이블 설계를 정하고 소유 서비스에 `V1__create_...sql`처럼 버전과 설명을 붙인 SQL을 작성합니다.
2. 소유 서비스를 실행하면 아직 반영되지 않은 마이그레이션이 적용되고, Flyway 이력 테이블에 기록됩니다. `ddl-auto=none`을 유지해 JPA가 별도로 스키마를 변경하지 않게 합니다.
3. 변경 SQL과 애플리케이션 코드를 함께 리뷰·커밋하고, 다른 개발자도 같은 파일을 적용합니다. 이미 공유 환경에 적용된 파일은 수정하지 않고 새 버전을 추가합니다. 버전 번호 충돌은 병합 전에 조정합니다.
4. Data 개발 환경에서는 Ingestion이 `weavo_data` 마이그레이션을 적용한 뒤 Data를 실행합니다. 배포 시에도 DB 변경이 해당 버전의 Data보다 먼저 반영되도록 배포 순서를 정해야 합니다.

현재는 폴더와 Flyway 연결만 준비되어 있으며 실제 테이블 생성 SQL은 설계 후 추가합니다. DB 스키마 마이그레이션과 사용자 스키마 리비전에 따른 데이터 세트 교체는 별개의 작업입니다.
