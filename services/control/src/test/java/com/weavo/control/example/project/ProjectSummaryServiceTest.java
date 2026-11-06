package com.weavo.control.example.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectSummaryServiceTest {
  @Test
  void returnsProjectFoundByRepository() {
    ProjectSummaryRepository repository = mock(ProjectSummaryRepository.class);
    var expected = new ProjectSummary(1L, "예시 프로젝트");
    when(repository.findById(1L)).thenReturn(Optional.of(expected));
    var service = new ProjectSummaryService(repository);
    assertEquals(Optional.of(expected), service.findProject(1L));
  }
}
