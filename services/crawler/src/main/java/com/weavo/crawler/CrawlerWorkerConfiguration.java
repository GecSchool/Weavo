package com.weavo.crawler;

import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.crawler.collection.application.FixedCollection;
import com.weavo.crawler.collection.infrastructure.messaging.CollectionJobProcessor;
import com.weavo.crawler.collection.infrastructure.messaging.ContractJson;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.siteaccess.SiteAccess;
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
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@Profile({"worker", "worker-test"})
@ConditionalOnProperty(name = "check", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(WorkerTiming.class)
class CrawlerWorkerConfiguration {
  @Bean
  JobWorker<CollectionExecute> jobWorker(
      WorkerSettings settings,
      WorkerTiming timing,
      SiteAccess sites,
      Environment environment,
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
                ? CollectionJobHandlers.forTesting()
                : CollectionJobHandlers.using(
                    new FixedCollection(
                        sites,
                        new FieldValueProcessor(new JavaFieldParser()),
                        settings.bucketName(),
                        transport::storeObject));
        var processor = new CollectionJobProcessor(new ContractJson(), handlers);
        return new JobWorker<>(processor, transport, control, timing, Clock.systemUTC());
      } catch (RuntimeException exception) {
        transport.close();
        throw exception;
      }
    } catch (RuntimeException exception) {
      control.close();
      throw exception;
    }
  }

  @Bean
  ApplicationRunner startJobWorker(JobWorker<CollectionExecute> worker) {
    return args -> worker.start();
  }
}
