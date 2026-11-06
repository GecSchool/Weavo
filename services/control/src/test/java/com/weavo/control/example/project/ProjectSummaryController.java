package com.weavo.control.example.project;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 학습용 예시입니다. @RestController를 붙이지 않아 실제 API로 등록되지 않습니다.
public class ProjectSummaryController {
  private final ProjectSummaryService service;

  public ProjectSummaryController(ProjectSummaryService service) {
    this.service = service;
  }

  @GetMapping("/example/projects/{projectId}")
  public ResponseEntity<ProjectSummary> findProject(@PathVariable long projectId) {
    return ResponseEntity.of(service.findProject(projectId));
  }
}
