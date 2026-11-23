# Weavo 웹

Node.js 22.23.3과 npm을 사용합니다. nvm 사용자는 `nvm install` 후 `nvm use`로 버전을 맞춥니다.

```bash
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run build
```

기본 주소는 http://localhost:3000 이며 `/example`에서 프로젝트 목록 예시를 볼 수 있습니다. 이 화면은 고정 샘플을 조회하며 실제 API와 연결되지 않습니다.

## 제품 라우트

화면의 역할과 사용자 흐름은 [화면 및 사용자 경험 기획안](../../docs/ui-ux-screen-plan.md)을 따릅니다. 현재 제품 라우트에는 홈 초안과 화면별 제목 골격이 있습니다.

| 주소 | 화면 및 역할 |
| --- | --- |
| `/` | 랜딩 |
| `/login` | 로그인 |
| `/signup` | 이메일 인증, 회원가입 |
| `/reset-password` | 이메일 인증, 비밀번호 초기화 |
| `/account/settings` | 계정 정보, 로그아웃, 탈퇴 |
| `/projects` | 소유 프로젝트 목록, 진행 중인 작업 재진입 |
| `/projects/new` | 프로젝트·출처 등록, 초기 분석 시작 |
| `/projects/[projectId]` | 프로젝트 대시보드, 작업·문제 상세, 수동 수집 |
| `/projects/[projectId]/analyses/[analysisId]` | 분석 진행, 후보 검토·수정·승인 |
| `/projects/[projectId]/collection-settings` | 현재 적용 중인 수집 설정 조회, 변경 후보 편집 진입 |
| `/projects/[projectId]/settings` | 프로젝트 정보·이메일 알림, 출처 추가·삭제 진입, 프로젝트 삭제 |
| `/projects/[projectId]/data` | 데이터 목록, 필터·정렬, 레코드 상세 |
| `/projects/[projectId]/api-access` | API 사용 안내, 키 발급·폐기 |

현재 설정에서 수집 규칙을 변경하거나 프로젝트 설정에서 출처 변경을 시작하면, 해당 작업의 분석 주소에서 같은 후보 편집기를 사용합니다. 가입·초기화 단계와 레코드 상세·키 발급 등은 해당 화면에서 엽니다. `/example`과 `/components`는 개발용 화면입니다.

## 파일 구조

```text
src/
├── app/                              # Next.js 라우팅과 화면 조합
│   ├── layout.tsx                    # 공통 레이아웃과 Provider
│   ├── globals.css                   # 전역 스타일과 공통 토큰
│   └── example/
│       ├── page.tsx                  # 예시 페이지 (Server Component)
├── features/                         # 도메인별 기능
│   └── projects/
│       ├── components/               # 프로젝트 화면 요소와 테스트
│       │   ├── ProjectList.tsx
│       │   └── ProjectList.test.tsx
│       ├── model/                    # 훅, TanStack Query, 검증·변환
│       │   ├── useExampleProjects.ts
│       │   └── useProjectSearch.ts
│       ├── services/                 # 서버 요청 함수 (예시는 샘플 조회)
│       │   └── getExampleProjects.ts
│       └── types/                    # 프로젝트 관련 타입
│           └── project.ts
└── shared/                           # 여러 도메인에서 쓰는 공통 코드
    ├── components/                   # 공통 UI
    ├── model/                        # 공통 훅과 상태·검증 로직
    ├── services/                     # 공통 외부 통신
    ├── types/                        # 공통 타입
    └── providers/QueryProvider.tsx
```

- `app`은 라우팅과 화면 조합을 담당하며, 한 페이지에만 필요한 코드는 그 페이지 옆에 둘 수 있습니다.
- `features`는 `auth`(인증·계정), `projects`(목록·생성·대시보드·프로젝트 설정), `analysis`(분석·수집 설정), `data`(데이터 조회·API 키)로 나눕니다. 각 도메인에는 `components`, `model`, `services`, `types`를 두고, 아직 파일이 없는 폴더는 `.gitkeep`으로 추적합니다.
- `model`에 일반 커스텀 훅, TanStack Query 훅, 검증·변환 로직을 함께 둡니다. 별도의 `hooks`와 `queries` 폴더는 만들지 않습니다.
- `shared`의 같은 이름의 폴더에는 도메인에 종속되지 않는 공통 코드를 둡니다. 공통 UI는 `components/ui`에, QueryProvider는 `providers`에 있습니다.
- `page.tsx`는 기본적으로 Server Component로 두고, 상태와 상호작용이 필요한 컴포넌트에만 `use client`를 붙입니다.
- 예시의 `services`는 샘플 데이터만 반환합니다. 실제 API를 연결할 때 HTTP 상태·응답 형태 검증과 로딩·빈 결과·오류 처리를 구현합니다.
- 컴포넌트 파일과 타입은 PascalCase, 함수와 변수는 camelCase, 테스트 파일은 `대상명.test.tsx`를 사용합니다. 스타일은 Tailwind CSS 유틸리티와 전역 토큰을 사용합니다. 기존 화면의 브라우저 기본 스타일을 유지하기 위해 Tailwind Preflight는 사용하지 않습니다.

## 예시 흐름

`/example`은 `app/example/page.tsx`가 `features/projects/components/ProjectList.tsx`를 표시합니다. 목록 컴포넌트는 `model/useExampleProjects.ts`로 샘플을 조회하고 `model/useProjectSearch.ts`로 검색어와 표시할 결과를 관리합니다. 조회 훅이 사용하는 `services/getExampleProjects.ts`는 현재 하드코딩된 예시만 반환하며, 데이터 타입은 `types/project.ts`에 있습니다.

`ProjectList.test.tsx`는 샘플 조회를 테스트 대역으로 바꿔 검색·빈 결과·로딩·오류 화면을 확인합니다. 실제 프로젝트 API를 호출하거나 저장하지 않습니다.
