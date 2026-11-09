"use client";

import { useState } from "react";
import { twMerge } from "tailwind-merge";
import { ProjectCard } from "@/features/projects/components/ProjectCard";
import {
  Alert,
  AlertDescription,
  AlertDialog,
  AlertTitle,
  Badge,
  Button,
  Checkbox,
  Dialog,
  FormField,
  Input,
  IconButton,
  Label,
  Logo,
  RadioGroup,
  Spinner,
  Switch,
  Select,
  Separator,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
  Tooltip,
} from "@/shared/components/ui";

const styles = {
  page: "[min-height:100vh] bg-showcase-page text-secondary-foreground font-showcase",
  header:
    "[height:var(--size-showcase-header-height)] flex items-center justify-between py-0 px-showcase-header-x bg-background [border-bottom:var(--border-width-ui)_solid_var(--color-showcase-header-border)] max-showcase:py-0 max-showcase:px-showcase-20",
  brand:
    "flex items-center gap-3 text-[length:var(--text-body-xs)] font-bold [letter-spacing:var(--tracking-showcase-label)]",
  divider: "text-showcase-divider my-0 mx-showcase-3",
  headerNote:
    "text-showcase-label text-[length:var(--text-showcase-xs)] [letter-spacing:var(--tracking-showcase-note)] font-bold [&_span]:text-showcase-strong [&_span]:ml-3 max-showcase:hidden",
  content:
    "[max-width:var(--size-showcase-content-width)] py-0 px-6 my-0 mx-auto",
  intro:
    "flex [align-items:flex-end] justify-between gap-showcase-20 pt-showcase-72 px-0 pb-showcase-54 [border-bottom:var(--border-width-ui)_solid_var(--color-showcase-intro-border)] [&_h1]:mt-showcase-17 [&_h1]:mx-0 [&_h1]:mb-showcase-10 [&_h1]:text-[length:var(--text-showcase-display)] [&_h1]:[letter-spacing:var(--tracking-showcase-title)] [&_h1]:[line-height:var(--leading-showcase-heading)] [&_p]:m-0 [&_p]:text-showcase-description [&_p]:text-[length:var(--text-body-sm)] [&_p]:[line-height:var(--leading-showcase-body)] max-showcase:pt-showcase-45 max-showcase:px-0 max-showcase:pb-showcase-35 max-showcase:[align-items:start] max-showcase:flex-col max-showcase:[&_h1]:text-[length:var(--text-showcase-display-sm)]",
  eyebrow:
    "text-[length:var(--text-showcase-xs)] font-bold [letter-spacing:var(--tracking-showcase-eyebrow)] text-showcase-eyebrow flex items-center gap-2 [&_span]:[width:var(--size-showcase-dot)] [&_span]:[height:var(--size-showcase-dot)] [&_span]:[border-radius:50%] [&_span]:bg-showcase-status",
  sectionHead:
    "[&_span]:text-[length:var(--text-showcase-xs)] [&_span]:font-bold [&_span]:[letter-spacing:var(--tracking-showcase-eyebrow)] [&_span]:text-showcase-label [&_p]:m-0 [&_p]:text-showcase-description [&_p]:text-[length:var(--text-body-sm)] [&_p]:[line-height:var(--leading-showcase-body)] flex justify-between [align-items:end] mb-showcase-20 [&_h2]:text-[length:var(--text-showcase-heading)] [&_h2]:[letter-spacing:var(--tracking-showcase-section)] [&_h2]:mt-2 [&_h2]:mx-0 [&_h2]:mb-showcase-5 [&_code]:text-showcase-meta [&_code]:text-[length:var(--text-showcase-code)] [&_code]:font-mono max-showcase:[&_code]:hidden",
  rowLabel:
    "text-[length:var(--text-showcase-xs)] font-bold [letter-spacing:var(--tracking-showcase-eyebrow)] text-showcase-label mb-showcase-14",
  versionDot:
    "[width:var(--size-showcase-dot)] [height:var(--size-showcase-dot)] [border-radius:50%] bg-showcase-status",
  version:
    "[border:var(--border-width-ui)_solid_var(--color-showcase-version-border)] rounded-md bg-background py-showcase-11 px-showcase-13 text-showcase-description text-[length:var(--text-showcase-xs)] [letter-spacing:var(--tracking-showcase-version)] font-bold flex items-center gap-2 whitespace-nowrap [&_strong]:text-showcase-version-text [&_strong]:ml-showcase-6",
  section: "pt-showcase-48 px-0 pb-0",
  panel:
    "bg-background [border:var(--border-width-ui)_solid_var(--color-showcase-panel-border)] [border-radius:var(--radius-showcase-panel)] p-showcase-28 [box-shadow:var(--shadow-showcase-panel)] max-showcase:p-showcase-20",
  tablePanel:
    "bg-background [border:var(--border-width-ui)_solid_var(--color-showcase-panel-border)] [border-radius:var(--radius-showcase-panel)] p-6 [box-shadow:var(--shadow-showcase-panel)] max-showcase:[overflow:auto]",
  buttonRow: "flex items-center flex-wrap gap-3 [&_+_div]:my-6 [&_+_div]:mx-0",
  formGrid:
    "grid [grid-template-columns:repeat(2,_minmax(0,_1fr))] gap-y-showcase-25 gap-x-showcase-28 max-showcase:[grid-template-columns:1fr]",
  wide: "[grid-column:1/-1] max-showcase:[grid-column:auto]",
  checks:
    "[grid-column:1/-1] flex flex-wrap gap-showcase-30 [border-top:var(--border-width-ui)_solid_var(--color-showcase-check-border)] pt-showcase-20 [&_label]:flex [&_label]:items-center [&_label]:gap-2 [&_label]:font-normal max-showcase:[grid-column:auto] max-showcase:flex-col max-showcase:gap-showcase-14",
  feedbackGrid:
    "grid [grid-template-columns:1fr_1fr] gap-showcase-48 max-showcase:[grid-template-columns:1fr]",
  badges: "flex gap-2 flex-wrap mb-showcase-30",
  skeletons:
    "grid gap-showcase-9 [max-width:var(--size-showcase-skeleton-width)] [&_div:nth-child(2)]:[width:80%] [&_div:nth-child(3)]:[width:55%]",
  alerts: "grid gap-3",
  dataGrid:
    "grid [grid-template-columns:0.85fr_1.6fr] gap-showcase-20 max-showcase:[grid-template-columns:1fr]",
  tableTitle:
    "flex justify-between items-center mb-showcase-20 [&_h3]:mt-0 [&_h3]:mx-0 [&_h3]:mb-showcase-5 [&_h3]:text-[length:var(--text-showcase-md)] [&_p]:m-0 [&_p]:text-showcase-meta [&_p]:text-[length:var(--text-body-xs)]",
  dialogGrid:
    "grid [grid-template-columns:1fr_1fr] gap-showcase-20 bg-showcase-dialog-background max-showcase:[grid-template-columns:1fr]",
  dialogPreview:
    "[min-height:var(--size-showcase-dialog-min-height)] p-6 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-md bg-background flex flex-col [&_p]:text-muted-foreground [&_p]:text-[length:var(--text-showcase-sm)]",
  previewTitle:
    "flex justify-between text-[length:var(--text-title-base)] font-semibold [&_span]:text-showcase-dialog-icon [&_span]:font-normal",
  previewActions: "flex justify-end gap-2 mt-auto",
  footer:
    "flex justify-between pt-showcase-55 px-0 pb-showcase-36 text-showcase-footer text-[length:var(--text-showcase-xs)] font-bold [letter-spacing:var(--tracking-showcase-footer)] max-showcase:gap-showcase-20",
};

export default function ComponentsPage() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div className={styles.brand}>
          <Logo /> <span className={styles.divider}>/</span> DESIGN SYSTEM
        </div>
        <span className={styles.headerNote}>
          COMPONENT LIBRARY <span>01</span>
        </span>
      </header>
      <div className={styles.content}>
        <div className={styles.intro}>
          <div>
            <div className={styles.eyebrow}>
              <span /> WEAVO UI FOUNDATIONS
            </div>
            <h1>공통 컴포넌트</h1>
            <p>
              Weavo 화면을 구성하는 기본 요소와 다양한 상태를 한곳에 모았습니다.
            </p>
          </div>
          <div className={styles.version}>
            <span className={styles.versionDot} /> COMPONENT PREVIEW{" "}
            <strong>v 1.0</strong>
          </div>
        </div>
        <section className={styles.section}>
          <div className={styles.sectionHead}>
            <div>
              <span>01 / ACTIONS</span>
              <h2>버튼</h2>
              <p>사용자의 행동과 중요도에 따라 버튼을 선택합니다.</p>
            </div>
            <code>Button</code>
          </div>
          <div className={styles.panel}>
            <div className={styles.rowLabel}>VARIANTS</div>
            <div className={styles.buttonRow}>
              <Button>기본 버튼</Button>
              <Button variant="destructive">삭제하기</Button>
              <Button variant="outline">보조 버튼</Button>
              <Button variant="secondary">Secondary</Button>
              <Button variant="ghost">Ghost</Button>
              <Button variant="link">자세히 보기</Button>
            </div>
            <Separator />
            <div className={styles.rowLabel}>SIZES & STATES</div>
            <div className={styles.buttonRow}>
              <Button size="sm">Small</Button>
              <Button>Default</Button>
              <Button size="lg">Large</Button>
              <Tooltip content="Add a source">
                <IconButton icon="add" label="Add source" variant="default" />
              </Tooltip>
              <Tooltip content="Copy API URL">
                <IconButton
                  icon="copy"
                  label="Copy API URL"
                  variant="outline"
                />
              </Tooltip>
              <Tooltip content="More actions">
                <IconButton icon="more" label="More actions" />
              </Tooltip>
              <Button disabled>비활성화</Button>
              <Button variant="outline" disabled>
                비활성화
              </Button>
            </div>
          </div>
        </section>
        <section className={styles.section}>
          <div className={styles.sectionHead}>
            <div>
              <span>02 / FORM ELEMENTS</span>
              <h2>입력 요소</h2>
              <p>명확한 레이블과 피드백으로 입력을 안내합니다.</p>
            </div>
            <code>Input · Select · Checkbox</code>
          </div>
          <div className={`${styles.panel} ${styles.formGrid}`}>
            <FormField
              id="project-name"
              label="프로젝트 이름"
              hint="프로젝트를 구분할 수 있는 이름을 입력해 주세요."
              required
            >
              {(props) => <Input {...props} placeholder="예: 공공 지원 사업" />}
            </FormField>
            <FormField
              id="source-url"
              label="출처 URL"
              error="올바른 URL을 입력해 주세요."
            >
              {(props) => <Input {...props} defaultValue="weavo.example" />}
            </FormField>
            <FormField id="field-type" label="필드 타입">
              {(props) => (
                <Select
                  {...props}
                  defaultValue="text"
                  options={[
                    { value: "text", label: "텍스트" },
                    { value: "number", label: "숫자" },
                    { value: "date", label: "날짜" },
                  ]}
                />
              )}
            </FormField>
            <FormField id="disabled-input" label="비활성화">
              {(props) => (
                <Input {...props} placeholder="입력할 수 없습니다" disabled />
              )}
            </FormField>
            <div className={styles.wide}>
              <FormField
                id="description"
                label="프로젝트 설명"
                hint="수집하려는 데이터에 대한 설명을 작성해 주세요."
              >
                {(props) => (
                  <Textarea
                    {...props}
                    placeholder="어떤 데이터를 수집하고 싶으신가요?"
                    rows={3}
                  />
                )}
              </FormField>
            </div>
            <RadioGroup
              name="field-location"
              legend="Field location"
              defaultValue="list"
              options={[
                { value: "list", label: "List page" },
                { value: "detail", label: "Detail page" },
                { value: "unavailable", label: "Unavailable", disabled: true },
              ]}
            />
            <div className="grid content-start gap-2">
              <Label htmlFor="email-notifications">Email notifications</Label>
              <Switch id="email-notifications" defaultChecked />
              <Label htmlFor="disabled-notifications">
                Unavailable notifications
              </Label>
              <Switch id="disabled-notifications" disabled />
            </div>
            <div className={styles.checks}>
              <Label>
                <Checkbox defaultChecked /> 수집 결과 알림 받기
              </Label>
              <Label>
                <Checkbox /> 변경 사항 알림 받기
              </Label>
              <Label>
                <Checkbox disabled /> 비활성화
              </Label>
            </div>
          </div>
        </section>
        <section className={styles.section}>
          <div className={styles.sectionHead}>
            <div>
              <span>03 / FEEDBACK</span>
              <h2>상태와 피드백</h2>
              <p>현재 상태와 필요한 안내를 명확하게 전달합니다.</p>
            </div>
            <code>Badge · Alert · Skeleton</code>
          </div>
          <div className={`${styles.panel} ${styles.feedbackGrid}`}>
            <div>
              <div className={styles.rowLabel}>BADGES</div>
              <div className={styles.badges}>
                <Badge>기본</Badge>
                <Badge variant="secondary">검토 중</Badge>
                <Badge variant="outline">대기 중</Badge>
                <Badge variant="destructive">오류</Badge>
              </div>
              <div className={styles.rowLabel}>LOADING</div>
              <div className="mb-3 flex items-center gap-3">
                <Spinner size="sm" label="Loading data" />
                <Spinner label="Checking sources" />
                <Spinner size="lg" label="Analyzing" />
              </div>
              <div className={styles.skeletons}>
                <Skeleton />
                <Skeleton />
                <Skeleton />
              </div>
            </div>
            <div className={styles.alerts}>
              <Alert>
                <AlertTitle>수집 결과를 확인해 주세요</AlertTitle>
                <AlertDescription>
                  추출된 데이터를 검토한 후 설정을 완료할 수 있습니다.
                </AlertDescription>
              </Alert>
              <Alert variant="destructive">
                <AlertTitle>수집을 완료하지 못했어요</AlertTitle>
                <AlertDescription>
                  출처 URL을 확인하고 다시 시도해 주세요.
                </AlertDescription>
              </Alert>
            </div>
          </div>
        </section>
        <section className={styles.section}>
          <div className={styles.sectionHead}>
            <div>
              <span>04 / DATA DISPLAY</span>
              <h2>데이터 표시</h2>
              <p>정보를 구조화하고 필요한 내용을 빠르게 살펴봅니다.</p>
            </div>
            <code>Card · Table · Separator</code>
          </div>
          <div className={styles.dataGrid}>
            <ProjectCard
              name="Public grants monitor"
              purpose="Collect public grant opportunities from multiple sources."
              status="ready"
              lastSuccessfulUpdate="Today"
              href="/example"
            />
            <div className={styles.tablePanel}>
              <div className={styles.tableTitle}>
                <div>
                  <h3>최근 수집 데이터</h3>
                  <p>연결된 출처의 최신 결과입니다.</p>
                </div>
                <Button size="sm" variant="outline">
                  전체 보기
                </Button>
              </div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>제목</TableHead>
                    <TableHead>출처</TableHead>
                    <TableHead>상태</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell>청년 창업 지원 사업</TableCell>
                    <TableCell>서울시</TableCell>
                    <TableCell>
                      <Badge variant="secondary">수집 완료</Badge>
                    </TableCell>
                  </TableRow>
                  <TableRow data-state="selected">
                    <TableCell>2026 지역 혁신 공모</TableCell>
                    <TableCell>공공데이터</TableCell>
                    <TableCell>
                      <Badge variant="outline">검토 필요</Badge>
                    </TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell>소상공인 성장 지원</TableCell>
                    <TableCell>중소벤처기업부</TableCell>
                    <TableCell>
                      <Badge variant="secondary">수집 완료</Badge>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          </div>
        </section>
        <section className={styles.section}>
          <div className={styles.sectionHead}>
            <div>
              <span>05 / OVERLAYS</span>
              <h2>대화상자</h2>
              <p>편집과 확인이 필요한 작업에 사용합니다.</p>
            </div>
            <code>Dialog · AlertDialog</code>
          </div>
          <div className={twMerge(styles.panel, styles.dialogGrid)}>
            <div className={styles.dialogPreview}>
              <div className={styles.previewTitle}>
                프로젝트 수정 <span>×</span>
              </div>
              <p>프로젝트 정보를 수정합니다.</p>
              <div className={styles.previewActions}>
                <Button size="sm" onClick={() => setDialogOpen(true)}>
                  대화상자 열기
                </Button>
              </div>
            </div>
            <div className={styles.dialogPreview}>
              <div className={styles.previewTitle}>프로젝트를 삭제할까요?</div>
              <p>삭제 후에는 이 작업을 되돌릴 수 없습니다.</p>
              <div className={styles.previewActions}>
                <Button size="sm" variant="outline">
                  취소
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={() => setConfirmOpen(true)}
                >
                  확인창 열기
                </Button>
              </div>
            </div>
          </div>
        </section>
        <footer className={styles.footer}>
          <span>
            WEAVO <span className={styles.divider}>/</span> COMPONENT LIBRARY
          </span>
          <span>Built for clarity and consistency.</span>
        </footer>
      </div>
      <Dialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title="프로젝트 수정"
        description="프로젝트 정보를 수정합니다."
        actions={<Button onClick={() => setDialogOpen(false)}>확인</Button>}
      />
      <AlertDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="프로젝트를 삭제할까요?"
        description="삭제 후에는 이 작업을 되돌릴 수 없습니다."
        actions={
          <>
            <Button variant="outline" onClick={() => setConfirmOpen(false)}>
              취소
            </Button>
            <Button variant="destructive" onClick={() => setConfirmOpen(false)}>
              삭제
            </Button>
          </>
        }
      />
    </main>
  );
}
