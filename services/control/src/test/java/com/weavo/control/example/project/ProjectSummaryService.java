package com.weavo.control.example.project;

import java.util.Optional;

public class ProjectSummaryService {
  private final ProjectSummaryRepository repository;

  public ProjectSummaryService(ProjectSummaryRepository repository) {
    this.repository = repository;
  }

  public Optional<ProjectSummary> findProject(long projectId) {
    return repository.findById(projectId);
  }
}
