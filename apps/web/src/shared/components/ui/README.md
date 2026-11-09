# 공통 UI

Figma `Review / Lanorx UI`의 기본 형태를 Weavo의 React와 Tailwind CSS에 맞춰 구현했습니다. Select는 Radix를 사용합니다.

- `Icon`: `name`과 `size`로 공통 아이콘을 표시합니다. 기본은 장식용이며 단독 의미가 있으면 `label`을 지정합니다.
- `IconButton`: `icon`과 필수 `label`로 접근 가능한 아이콘 버튼을 만듭니다.
- `Tooltip`: `TooltipProvider` 안에서 포커스 가능한 트리거와 `content`를 받습니다. 툴팁은 버튼의 접근 가능한 이름을 대신하지 않습니다. 비활성 버튼에는 이유를 별도 텍스트로 안내합니다.
- `Logo`: `full` 또는 `mark`로 Weavo 브랜드를 표시합니다. 시각 형태는 Figma 브랜드 컴포넌트를 기준으로 합니다.
- `RadioGroup`: `name`, `legend`, `options`로 네이티브 라디오 그룹을 구성합니다. `value`/`onValueChange` 또는 `defaultValue`를 사용합니다.
- `Switch`: 레이블을 연결하는 네이티브 체크박스 기반 on/off 제어입니다. `checked`/`onChange` 또는 `defaultChecked`를 사용합니다.
- `Spinner`: 크기와 로딩 레이블을 지정하며, 주변 텍스트가 상태를 알릴 때 `decorative`를 사용할 수 있습니다.
- `Button`: `variant`와 `size` 지정. 기본 `type`은 `button`이며 제출은 `type="submit"`을 지정합니다.
- `Input`, `Textarea`, `Checkbox`, `Label`: HTML 속성을 받습니다. `Select`는 Radix 기반이며 `options`, `value`/`defaultValue`, `onValueChange`, `size`를 사용합니다. 옵션에는 비어 있지 않은 고유 `value`가 필요합니다.
- `FormField`: 입력 함수에 `id`, `required`, `aria-invalid`, `aria-describedby`를 전달합니다. 오류 문자열은 제출 검증 결과에 따라 전달합니다.
- `Badge`, `Alert`, `Card`, `Table`, `Skeleton`, `Separator`: 기본 시각 요소. 동적 오류 알림에는 필요한 경우 `role="alert"`를 지정합니다.
- `Dialog`, `AlertDialog`: 클라이언트에서 `open`과 `onOpenChange`로 제어합니다. `actions`에 취소·확인 버튼을 전달하고 해당 이벤트에서 닫습니다. 브라우저의 모달 dialog를 사용합니다.

```tsx
<FormField id="name" label="프로젝트 이름" error={error} required>
  {(field) => <Input {...field} value={name} onChange={handleChange} />}
</FormField>
```

시각 토큰은 현재 Figma의 Lanorx 기반 Light 값을 Tailwind 유틸리티에 적용했습니다. Inter를 사용하고 한글은 시스템 폰트로 대체합니다. 다크 테마는 아직 지원하지 않습니다. 화면별 도메인 컴포넌트, 데이터 연동 및 Code Connect는 포함하지 않습니다.

색상·글꼴·글자 크기·radius 토큰은 `src/app/globals.css`에 정의합니다. `--ui-*`는 Figma의 Lanorx / Color Light 의미 토큰에 대응하고, `--color-neutral-*`은 원시 색상입니다. `showcase-*`와 `example-*`은 기존 예시 화면 전용 값이며 Figma 토큰이 아닙니다. Figma 변경 시 코드를 자동 동기화하지 않으므로 두 정의를 함께 확인해야 합니다.

간격은 Figma의 `spacing/1`, `2`, `3`, `4`, `6`, `8`에 대응하는 `--spacing-1`, `2`, `3`, `4`, `6`, `8`을 사용하며 Tailwind에서는 `p-4`, `gap-2`처럼 씁니다. `spacing-ui-*`와 `spacing-showcase-*`, `spacing-example-*`은 기존 화면을 유지하기 위해 추가한 코드 전용 값입니다. Figma 표준 간격이 아니므로 새 UI에서는 우선 표준 토큰을 사용합니다.

프로젝트별 카드와 상태는 `features/projects/components/ProjectCard.tsx`에 둡니다. 기본 `Card`는 도메인에 종속되지 않는 컨테이너입니다.

공통 크기 `--size-*`, 선 두께 `--border-width-*`/`--stroke-icon`, `--duration-ui-*`, `--shadow-ui-*`는 `src/app/globals.css`를 참조합니다. Figma에서는 `Weavo / UI metrics`와 `Weavo / Shadow`에 대응합니다. Radix의 숫자 offset은 `ui-metrics.ts`에 있으며 Figma spacing과 자동 동기화되지 않습니다. Figma와 코드는 별도 정의이므로 변경 시 두 쪽을 확인합니다.

기본 컴포넌트는 `Button.tsx`, `Input.tsx`, `Badge.tsx`처럼 기능별 파일에서 관리합니다. 함께 사용하는 `Alert`, `Card`, `Table`의 하위 요소는 각각의 파일에 두며, 외부에서는 기존처럼 `@/shared/components/ui`로 가져올 수 있습니다. 입력과 설명에 공통인 클래스는 `fieldStyles.ts`, 클래스 결합은 `classNames.ts`에서 관리합니다. Inter 폰트는 앱 루트 레이아웃에서 불러옵니다. 동작·접근성 테스트도 관련 컴포넌트별 테스트 파일에 있습니다.
