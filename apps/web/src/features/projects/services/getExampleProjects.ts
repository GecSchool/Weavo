import type { Project } from "../types/project";

// 구조 설명용 샘플입니다. 실제 API 요청이나 서버 데이터를 대신하지 않습니다.
export async function getExampleProjects(): Promise<Project[]> {
  return [
    { id: "sample-1", name: "채용 공고 모아보기", sourceCount: 3 },
    { id: "sample-2", name: "관심 분야 뉴스", sourceCount: 2 },
  ];
}
