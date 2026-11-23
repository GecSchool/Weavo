package com.weavo.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.weavo.analysis.joint.application.JointRecommender;
import com.weavo.analysis.source.application.SourceDiscovery;
import com.weavo.worker.application.JobWorker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SimpleCommandLinePropertySource;

class AnalysisWorkerConfigurationTest {
  @Test
  void checkModeDoesNotCreateAConsumerEvenInTheWorkerTestProfile() {
    new ApplicationContextRunner()
        .withUserConfiguration(AnalysisWorkerConfiguration.class)
        .withInitializer(context -> {
          context.getEnvironment().setActiveProfiles("worker-test");
          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(new SimpleCommandLinePropertySource("--check"));
        })
        .run(context -> assertThat(context.getBeansOfType(JobWorker.class)).isEmpty());
  }

  @Test
  void workerProfileWiresTheLiveHandlerWithoutStartingPollingInTheContextTest() {
    var local =
        new WorkerSettings("local", "ap-northeast-2", "http://127.0.0.1:4566", null, null, null);
    new ApplicationContextRunner()
        .withUserConfiguration(AnalysisWorkerConfiguration.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("worker"))
        .withPropertyValues(
            "WEAVO_CONTROL_URL=http://127.0.0.1:8082", "WEAVO_SERVICE_TOKEN=fixture-token")
        .withBean(WorkerSettings.class, () -> local)
        .withBean(SourceDiscovery.class, () -> org.mockito.Mockito.mock(SourceDiscovery.class))
        .withBean(JointRecommender.class, () -> (input, budget) -> List.of())
        .run(context -> assertThat(context).hasSingleBean(JobWorker.class));
  }

  @Test
  void defaultStartupDoesNotActivateAFixedResultConsumer() {
    new ApplicationContextRunner()
        .withUserConfiguration(AnalysisWorkerConfiguration.class)
        .run(context -> assertThat(context.getBeansOfType(JobWorker.class)).isEmpty());
  }
}
