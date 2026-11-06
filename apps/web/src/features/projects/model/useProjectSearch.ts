import { useState } from "react";
import type { Project } from "../types/project";

export function useProjectSearch(projects: Project[]) {
  const [search, setSearch] = useState("");
  const matchingProjects = projects.filter((project) =>
    project.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  return { search, setSearch, matchingProjects };
}
