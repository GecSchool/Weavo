package com.weavo.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import com.weavo.worker.application.JobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SimpleCommandLinePropertySource;

class CrawlerWorkerConfigurationTest {
  @Test
  void checkModeDoesNotCreateAConsumerEvenInTheWorkerTestProfile() {
    new ApplicationContextRunner()
        .withUserConfiguration(CrawlerWorkerConfiguration.class)
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
  void defaultStartupDoesNotActivateAFixedResultConsumer() {
    new ApplicationContextRunner()
        .withUserConfiguration(CrawlerWorkerConfiguration.class)
        .run(context -> assertThat(context.getBeansOfType(JobWorker.class)).isEmpty());
  }
}
