import Link from "next/link";
import { ProjectList } from "@/features/projects/components/ProjectList";
import styles from "./page.module.css";

export default function ExamplePage() {
  return (
    <main className={styles.page}>
      <Link href="/">← 홈으로</Link>
      <h1>프로젝트 목록 예시</h1>
      <p>
        파일 구조를 설명하기 위한 예시 화면입니다. 표시된 프로젝트는 실제
        데이터가 아닙니다.
      </p>
      <ProjectList />
    </main>
  );
}
