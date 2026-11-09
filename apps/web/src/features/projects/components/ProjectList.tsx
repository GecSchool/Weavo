"use client";

import { useExampleProjects } from "../model/useExampleProjects";
import { useProjectSearch } from "../model/useProjectSearch";

const styles = {
  search:
    "block [width:min(100%,_var(--size-example-search-max-width))] p-example-sm mt-example-xs [border:var(--border-width-ui)_solid_var(--color-example-border)] [border-radius:var(--radius)] [font-family:inherit] [font-size:inherit] [font-weight:inherit] [line-height:inherit]",
  list: "grid gap-example-sm p-0 [list-style:none]",
  card: "p-example-md [border:var(--border-width-ui)_solid_var(--color-example-border)] [border-radius:var(--radius)] [&_h3]:m-0 [&_p]:m-0 [&_p]:mt-example-xs [&_p]:[color:var(--color-example-muted)]",
};

export function ProjectList() {
  const { data: projects = [], isPending, isError } = useExampleProjects();
  const { search, setSearch, matchingProjects } = useProjectSearch(projects);

  if (isPending) return <p role="status">프로젝트를 불러오는 중입니다.</p>;
  if (isError) return <p role="alert">프로젝트를 불러오지 못했습니다.</p>;

  return (
    <section aria-labelledby="projects-heading">
      <h2 id="projects-heading">프로젝트</h2>
      <label htmlFor="project-search">프로젝트 이름 검색</label>
      <input
        className={styles.search}
        id="project-search"
        type="search"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      {matchingProjects.length === 0 ? (
        <p role="status">조건에 맞는 프로젝트가 없습니다.</p>
      ) : (
        <ul className={styles.list}>
          {matchingProjects.map((project) => (
            <li className={styles.card} key={project.id}>
              <h3>{project.name}</h3>
              <p>연결된 출처 {project.sourceCount}개</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
