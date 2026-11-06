import Link from "next/link";
import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.page}>
      <h1>Weavo</h1>
      <p>웹 데이터를 통합하고 API로 활용하세요.</p>
      <Link href="/example">프로젝트 목록 예시 보기</Link>
    </main>
  );
}
