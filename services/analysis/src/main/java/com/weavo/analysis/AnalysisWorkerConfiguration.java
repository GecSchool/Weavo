package com.weavo.analysis;

import com.weavo.analysis.approval.application.FinalAnalysis;
import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.analysis.job.application.AnalysisJobHandlers.Purpose;
import com.weavo.analysis.job.infrastructure.messaging.AnalysisJobProcessor;
import com.weavo.analysis.job.infrastructure.messaging.ContractJson;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.joint.application.AnalysisArtifacts;
import com.weavo.analysis.joint.application.JointAnalysis;
import com.weavo.analysis.joint.application.JointRecommender;
import com.weavo.analysis.source.application.SourceDiscovery;
import com.weavo.worker.application.JobWorker;
import com.weavo.worker.application.WorkerTiming;
import com.weavo.worker.infrastructure.AwsJobTransport;
import com.weavo.worker.infrastructure.ControlAttemptClient;
import java.net.URI;
import java.time.Clock;
import java.util.EnumMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@Profile({"worker", "worker-test"})
@ConditionalOnProperty(name = "check", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(WorkerTiming.class)
class AnalysisWorkerConfiguration {
  @Bean
  JobWorker<AnalysisExecute> jobWorker(
      WorkerSettings settings,
      WorkerTiming timing,
      Environment environment,
      ObjectProvider<SourceDiscovery> discovery,
      ObjectProvider<JointRecommender> recommendation,
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
      try {
        var handlers =
            environment.acceptsProfiles(Profiles.of("worker-test"))
                ? AnalysisJobHandlers.forTesting()
                : liveHandlers(
                    settings, transport, discovery.getObject(), recommendation.getObject());
        var processor = new AnalysisJobProcessor(new ContractJson(), handlers);
        return new JobWorker<>(processor, transport, control, timing, Clock.systemUTC());
      } catch (RuntimeException failure) {
        transport.close();
        throw failure;
      }
    } catch (RuntimeException exception) {
      control.close();
      throw exception;
    }
  }

  private static AnalysisJobHandlers liveHandlers(
      WorkerSettings settings,
      AwsJobTransport transport,
      SourceDiscovery discovery,
      JointRecommender recommender) {
    var artifacts =
        new AnalysisArtifacts(settings.bucketName(), transport::readObject, transport::storeObject);
    var joint = new JointAnalysis(discovery::analyze, artifacts, recommender);
    var finalAnalysis = new FinalAnalysis(joint, discovery::verify, artifacts);
    var available = new EnumMap<Purpose, AnalysisJobHandlers.Handler>(Purpose.class);
    for (Purpose purpose : Purpose.values()) {
      available.put(purpose, finalAnalysis::execute);
    }
    return new AnalysisJobHandlers(available);
  }

  @Bean
  ApplicationRunner startJobWorker(JobWorker<AnalysisExecute> worker) {
    return args -> worker.start();
  }
}
