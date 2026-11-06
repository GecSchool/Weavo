import { useQuery } from "@tanstack/react-query";
import { getExampleProjects } from "../services/getExampleProjects";

// 샘플 조회용 키입니다. 실제 프로젝트 API 연결 시 별도로 정의합니다.
export const EXAMPLE_PROJECTS_QUERY_KEY = ["example-projects"] as const;

export function useExampleProjects() {
  return useQuery({
    queryKey: EXAMPLE_PROJECTS_QUERY_KEY,
    queryFn: getExampleProjects,
  });
}
