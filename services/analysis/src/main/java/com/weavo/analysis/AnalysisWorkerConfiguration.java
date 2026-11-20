package com.weavo.analysis;

import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.analysis.job.infrastructure.messaging.AnalysisJobProcessor;
import com.weavo.analysis.job.infrastructure.messaging.ContractJson;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.worker.application.JobWorker;
import com.weavo.worker.application.WorkerTiming;
import com.weavo.worker.infrastructure.AwsJobTransport;
import com.weavo.worker.infrastructure.ControlAttemptClient;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker-test")
@ConditionalOnProperty(name = "check", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(WorkerTiming.class)
class AnalysisWorkerConfiguration {
  @Bean
  JobWorker<AnalysisExecute> jobWorker(
      WorkerSettings settings,
      WorkerTiming timing,
      @Value("${WEAVO_CONTROL_URL:}") String controlUrl,
      @Value("${WEAVO_SERVICE_TOKEN:}") String serviceToken) {
    settings.validate();
    var control =
        new ControlAttemptClient(
            controlUrl, serviceToken, timing.controlTimeout(), settings.isLocal());
    try {
      var transport =
          AwsJobTransport.open(
              settings.region(),
              settings.isLocal() ? URI.create(settings.endpoint()) : null,
              settings.bucketName(),
              settings.inputQueueNames(),
              settings.resultQueueName(),
              timing.awsTimeout());
      var processor =
          new AnalysisJobProcessor(new ContractJson(), AnalysisJobHandlers.forTesting());
      return new JobWorker<>(processor, transport, control, timing, Clock.systemUTC());
    } catch (RuntimeException exception) {
      control.close();
      throw exception;
    }
  }

  @Bean
  ApplicationRunner startJobWorker(JobWorker<AnalysisExecute> worker) {
    return args -> worker.start();
  }
}
