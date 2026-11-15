# 팀 내부 API·메시지 계약

팀의 HTTP API와 서비스 간 메시지 형식이다. 계약 파일은 실제 서버 구현이나 서비스 연결 완료를 의미하지 않는다.

## 문서 구성

- `openapi/`: Auth·Control·Data·내부 HTTP API의 OpenAPI 3.1 JSON.
- `asyncapi/`: Analysis·Crawler·Control·Ingestion별 AsyncAPI 3.0 JSON. 송수신 방향은 각 서비스를 기준으로 하며 큐 주소는 환경 설정으로 관리한다.
- `schemas/`: SQS 메시지의 공통 envelope와 payload에 대한 JSON Schema Draft 07. Scheduler의 정기 신호는 별도 형식을 사용한다.
- `examples/messages/`: 메시지 payload 예시. 모든 가능한 입력을 나타내지는 않는다.
- [API 구현 시 필요한 처리 규칙](api-implementation-notes.md): 여러 서비스 또는 요청의 상태를 함께 판단하는 규칙.
- [계약 구현과 통합 확인 사항](implementation-checklist.md): 형식 검사 외의 구현·통합 시험 항목.
- `inventory.json`: API와 메시지의 목록·송수신 방향을 검사하는 데이터.
- [서비스 메시지 명세](../service-message-contracts.md), [기능별 전달 흐름](../service-message-flows.md), [S3 저장 명세](../s3-storage-contract.md): 메시지 내용·실행 순서·객체 저장 계약.

## 계약 사용 시 주의

- JSON Schema는 DB 스키마와 커넥터의 일치, S3 객체 소유·존재, event_id 중복 처리, 동일 출처 판정이나 실행 시간 제한을 대신 검증하지 않는다. 조건별 필수 여부는 메시지 명세와 스키마의 조건을 함께 확인한다.
- `format_version`, 후보 `revision`, `schema_version`, `connector_revision`, `navigation_revision`은 서로 다르다. Scheduler 등록 템플릿과 실제 수신 신호의 시각 형식도 구분한다.
- 스키마에 명시되지 않은 필드를 자동 금지하지 않는다. URL 형식 검사는 실제 접근·리다이렉트 안전성 검사를 대체하지 않는다.
- 동일 이벤트 재발행은 전체 envelope·payload·event_id를 보존한다. Data API의 사용자 필드는 프로젝트 활성 스키마에 따라 달라진다.
- 계약과 미리보기는 팀 내부 개발용이다. 사용자 대상 Swagger UI·명세 다운로드는 MVP 범위가 아니다.

## 검증

저장소 루트에서 아래와 같이 별도 가상 환경을 만들어 검증할 수 있다. 애플리케이션이나 인프라를 실행하지 않는다.

```bash
python3 -m venv /tmp/weavo-contract-venv
/tmp/weavo-contract-venv/bin/pip install -r docs/contracts/requirements.txt
/tmp/weavo-contract-venv/bin/python docs/contracts/validate.py
```

이 검사는 Draft 07 문법, 원문 예시 일치, 13개 공통 envelope와 정기 신호, 원문 예시와 목적별 입력·추출 방식 등 정상·잘못된 입력, OpenAPI 형식과 API 목록, AsyncAPI 서비스별 메시지 목록·방향, 20개 흐름의 제목과 명시된 메시지 종류의 일치를 확인한다. 모든 의미·조건을 검증하거나 서비스를 호출하지는 않는다.

AsyncAPI 3.0과 참조 파일의 공식 파서 검사는 선택적으로 별도 임시 폴더에 도구를 설치해 수행한다.

```bash
npm install --prefix /tmp/weavo-asyncapi-check --no-save @asyncapi/parser@3.6.6
node docs/contracts/validate-asyncapi.cjs /tmp/weavo-asyncapi-check/node_modules/@asyncapi/parser
```

파서가 AsyncAPI 3.1 사용을 권장하는 경고를 내더라도 현재 파일은 3.0 기준으로 검사한다. 문서 미리보기 도구를 사용하면 `openapi/*.json` 또는 `asyncapi/*.json`을 열 수 있다. AsyncAPI는 `schemas/`의 상대 참조를 사용하므로 파일 하나만 떼어내지 말고 이 디렉터리 구조를 유지해야 한다. HTTP API 서버가 없어도 명세 파일은 볼 수 있지만, 실제 호출 결과를 검증할 수는 없다.

## 팀 내부 미리보기

OpenAPI는 Spring 서버를 실행하지 않아도 로컬에서 볼 수 있다. 다음은 Swagger UI와 같은 용도로 OpenAPI를 표시하는 Redoc 커뮤니티 미리보기의 예시다. Node 22 환경에서 확인했으며 도구 다운로드에는 네트워크가 필요하다.

```bash
npx --yes @redocly/cli@1.34.15 preview-docs docs/contracts/openapi/auth.json --host 127.0.0.1 --port 8080
```

브라우저에서 `http://127.0.0.1:8080`을 열고, 다른 서비스를 보려면 파일명을 `control.json`, `data.json`, `internal.json`으로 바꾼다. 종료는 터미널에서 `Ctrl+C`다. 이 화면은 초안 문서이며 실제 서비스 호출 검증 결과가 아니다.

AsyncAPI는 팀의 Node 22 환경에서 다음처럼 **임시 HTML**로 생성해 볼 수 있다. 처음에는 도구 설치에 시간이 걸릴 수 있다. 아래 출력 위치는 생성된 문서 전용이며 이미 내용이 있으면 다른 빈 경로를 선택한다.

```bash
npm install --prefix /tmp/weavo-asyncapi-preview --no-save @asyncapi/generator@2.8.4 @asyncapi/html-template@3.5.4
node docs/contracts/preview-asyncapi.cjs crawler /tmp/weavo-crawler-doc /tmp/weavo-asyncapi-preview/node_modules
open /tmp/weavo-crawler-doc/index.html
```

`crawler`를 `analysis`, `control`, `ingestion`으로 바꾸고 출력 경로도 달리하면 해당 서비스 문서를 생성한다. `open`은 macOS 명령이며, 다른 운영체제에서는 생성된 `index.html`을 브라우저로 열면 된다. 생성물과 임시 도구는 저장소에 커밋하지 않는다. 로컬 파일 열람은 외부 사용자에게 문서를 배포하는 것이 아니다.

## 변경 시

1. 제품 동작은 [스펙](../spec-decision-log.md)과 [메시지 명세](../service-message-contracts.md)를 확인한다.
2. HTTP·메시지 형식을 바꾸면 구조화 계약, 관련 문서, 예시와 검증을 함께 갱신한다.
3. 생산자·소비자의 호환성과 형식 검사로 확인할 수 없는 업무 규칙을 검증한다.
