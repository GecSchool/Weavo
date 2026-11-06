package com.weavo.control.example.project;

import java.util.Optional;

public interface ProjectSummaryRepository {
  Optional<ProjectSummary> findById(long projectId);
}
