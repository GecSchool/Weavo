import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { getExampleProjects } from "../services/getExampleProjects";
import { ProjectList } from "./ProjectList";

vi.mock("../services/getExampleProjects", () => ({
  getExampleProjects: vi.fn(),
}));

function renderProjectList() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectList />
    </QueryClientProvider>,
  );
}

describe("ProjectList", () => {
  it("프로젝트 이름으로 목록을 검색한다", async () => {
    vi.mocked(getExampleProjects).mockResolvedValueOnce([
      { id: "1", name: "채용 공고", sourceCount: 2 },
      { id: "2", name: "기술 뉴스", sourceCount: 1 },
    ]);
    renderProjectList();
    fireEvent.change(
      await screen.findByRole("searchbox", { name: "프로젝트 이름 검색" }),
      {
        target: { value: "채용" },
      },
    );
    expect(screen.getByText("채용 공고")).toBeInTheDocument();
    expect(screen.queryByText("기술 뉴스")).not.toBeInTheDocument();
  });

  it("검색 결과가 없으면 안내한다", async () => {
    vi.mocked(getExampleProjects).mockResolvedValueOnce([
      { id: "1", name: "채용 공고", sourceCount: 2 },
    ]);
    renderProjectList();
    fireEvent.change(
      await screen.findByRole("searchbox", { name: "프로젝트 이름 검색" }),
      {
        target: { value: "없는 프로젝트" },
      },
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "조건에 맞는 프로젝트가 없습니다.",
    );
  });

  it("조회 실패를 안내한다", async () => {
    vi.mocked(getExampleProjects).mockRejectedValueOnce(new Error("조회 실패"));
    renderProjectList();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "프로젝트를 불러오지 못했습니다.",
    );
  });

  it("조회 중 상태를 안내한다", () => {
    vi.mocked(getExampleProjects).mockImplementationOnce(
      () => new Promise(() => {}),
    );
    renderProjectList();
    expect(screen.getByRole("status")).toHaveTextContent(
      "프로젝트를 불러오는 중입니다.",
    );
  });
});
