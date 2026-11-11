# 프론트·Spring 코드 컨벤션 환경

팀 코드 컨벤션 최종본의 형식·테스트·리뷰 기준과 각 서비스 README의 기능별 파일 구조를 사용한다. 업무 동작과 서비스 간 형식은 `spec-decision-log.md`, `service-message-contracts.md`, `s3-storage-contract.md`가 기준이다. Analysis·Crawler도 Java 서비스로 같은 기준을 적용한다.

## 처음 설정

1. `apps/web`에서 README에 명시된 Node 버전을 사용해 `npm ci`를 실행한다.
2. 저장소 루트에서 `./scripts/setup-git-hooks.sh`로 버전 관리되는 Git 훅을 활성화한다. 기존에 다른 `core.hooksPath`가 있다면 덮어쓰지 않는다.
3. Codex에서 프로젝트 훅을 검토하고 신뢰한다. CLI의 `/hooks`에서 확인할 수 있다. 글로벌 훅과 프로젝트 훅은 함께 실행된다.

## 검사 명령

- 프론트: `cd apps/web`에서 `npm run format:check`, `npm run lint -- --max-warnings=0`, `npm run typecheck`, `npm test`. 자동 포맷은 `npm run format`이다.
- Spring: 각 `services/{auth,control,data,ingestion,analysis,crawler}`에서 `./gradlew spotlessCheck`, `./gradlew test`. 자동 포맷은 `./gradlew spotlessApply`다. DB가 필요한 테스트는 해당 서비스 README의 로컬 환경이 필요하다.
- Git `pre-commit`은 스테이징된 변경에 해당하는 영역만 선택한다. 해당 영역 전체의 빠른 포맷·정적 검사를 수행하며 부분 스테이징된 코드 파일은 정확히 검사할 수 없으므로 거부한다. 테스트 전체는 CI와 작업별 검증에서 수행한다.
- `commit-msg`는 `feat`, `fix`, `refactor`, `test`, `docs`, `chore` 유형을 검사한다. Git의 merge/revert/fixup/squash 메시지도 허용한다.
- CI는 프론트 검사와 여섯 Spring 서비스의 Spotless·테스트를 수행한다. DB를 사용하는 네 서비스의 CI에만 PostgreSQL을 제공한다. CI는 모든 프로젝트에 대해 실행하며 필요하면 추후 변경 범위별로 최적화한다.

## Codex 검토

`UserPromptSubmit`에서 이번 요청 시작 전 작업 트리의 프론트·Spring 코드 상태를 임시 디렉터리에 해시로 기록한다. `Stop`에서는 이전 상태와 비교해 이번 요청에서 달라진 파일의 영역에 맞는 검토를 요청한다. 양쪽이 바뀌면 양쪽과 API 경계를 확인한다. 기준이 없으면 기존 변경을 이번 작업으로 추정하지 않고 건너뛴다. 이 방식은 세션에 참여하지 않은 다른 작업자가 같은 파일을 동시에 수정하면 구분하지 못하므로, 발견 시 사용자 변경을 보존한다.

`Stop`은 한 번만 Codex에게 검토를 요청하며 별도 모델을 실행하지 않는다. Codex의 검토는 자동 증명이 아니며 코드 리뷰를 대체하지 않는다. 이미 존재하는 전역 훅의 정책이 개별 동작에 영향을 줄 수 있다. 프로젝트 훅 설정 또는 스크립트를 갱신하면 Codex 신뢰 상태를 다시 확인한다.
