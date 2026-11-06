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

## 파일 구조

```text
src/
├── app/                              # Next.js 라우팅과 화면 조합
│   ├── layout.tsx                    # 공통 레이아웃과 Provider
│   ├── globals.css                   # 전역 스타일과 공통 토큰
│   └── example/
│       ├── page.tsx                  # 예시 페이지 (Server Component)
│       └── page.module.css
├── features/                         # 도메인별 기능
│   └── projects/
│       ├── components/               # 프로젝트 화면 요소와 테스트
│       │   ├── ProjectList.tsx
│       │   ├── ProjectList.module.css
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
- `features`는 프로젝트, 인증 등 도메인별로 묶습니다. 규모가 커지면 도메인 안에서 기능별로 나눕니다.
- `model`에 일반 커스텀 훅, TanStack Query 훅, 검증·변환 로직을 함께 둡니다. 별도의 `hooks`와 `queries` 폴더는 만들지 않습니다.
- `features` 하위 폴더는 필요할 때 만듭니다. `shared`의 같은 이름의 폴더에는 도메인에 종속되지 않는 공통 코드를 둡니다. 현재 `providers` 외 공통 폴더는 빈 자리입니다.
- `page.tsx`는 기본적으로 Server Component로 두고, 상태와 상호작용이 필요한 컴포넌트에만 `use client`를 붙입니다.
- 예시의 `services`는 샘플 데이터만 반환합니다. 실제 API를 연결할 때 HTTP 상태·응답 형태 검증과 로딩·빈 결과·오류 처리를 구현합니다.
- 컴포넌트 파일과 타입은 PascalCase, 함수와 변수는 camelCase, 테스트 파일은 `대상명.test.tsx`를 사용합니다. 스타일은 CSS Modules와 전역 토큰을 사용합니다.

## 예시 흐름

`/example`은 `app/example/page.tsx`가 `features/projects/components/ProjectList.tsx`를 표시합니다. 목록 컴포넌트는 `model/useExampleProjects.ts`로 샘플을 조회하고 `model/useProjectSearch.ts`로 검색어와 표시할 결과를 관리합니다. 조회 훅이 사용하는 `services/getExampleProjects.ts`는 현재 하드코딩된 예시만 반환하며, 데이터 타입은 `types/project.ts`에 있습니다.

`ProjectList.test.tsx`는 샘플 조회를 테스트 대역으로 바꿔 검색·빈 결과·로딩·오류 화면을 확인합니다. 실제 프로젝트 API를 호출하거나 저장하지 않습니다.
