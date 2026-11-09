import Link from "next/link";

const styles = {
  page: "[max-width:var(--size-example-page-max-width)] my-example-xl mx-auto py-0 px-example-md",
};

export default function Home() {
  return (
    <main className={styles.page}>
      <h1>Weavo</h1>
      <p>웹 데이터를 통합하고 API로 활용하세요.</p>
      <Link href="/example">프로젝트 목록 예시 보기</Link>
    </main>
  );
}
