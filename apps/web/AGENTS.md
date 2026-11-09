<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 프론트엔드 구조 및 검토

- `app`은 라우팅과 화면 조합, `features`는 도메인별 코드, `shared`는 여러 도메인에서 쓰는 공통 코드다. 기능별 `components`, `model`, `services`, `types`는 필요한 경우에만 둔다. 자세한 예시는 README를 따른다.
- Server Component를 기본으로 하고 상호작용과 브라우저 API가 필요한 범위에만 `use client`를 둔다.
- TypeScript strict를 유지하고 새 `any`를 피한다. 외부 입력은 검증한다. 컴포넌트는 PascalCase, 훅은 `use`로 시작한다. Next.js 예약 파일명은 프레임워크 규칙을 따른다.
- 파생 가능한 상태의 중복과 단순 계산·클릭 처리용 useEffect를 피한다. 로딩·빈 결과·오류와 접근성을 확인한다.
- 필요할 때 Vitest와 React Testing Library로 사용자 행동과 표시 결과를 검증한다. 변경 후 `npm run lint`, `npm run typecheck`, `npm test` 중 적절한 검사를 실행한다.
- Prettier는 `npm run format` / `npm run format:check`로 실행한다.
