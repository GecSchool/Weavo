package com.weavo.analysis;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@EnableConfigurationProperties(WorkerSettings.class)
public class AnalysisApplication {
  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(AnalysisApplication.class, args);
    if (isResourceCheck(context.getEnvironment())) {
      context.close();
    }
  }

  @Bean
  ApplicationRunner resourceCheckRunner(WorkerSettings settings, Environment environment) {
    return args -> {
      if (isResourceCheck(environment)) {
        new AwsResourceChecker(settings).check();
      }
    };
  }

  private static boolean isResourceCheck(Environment environment) {
    String check = environment.getProperty("check");
    return check != null && !"false".equalsIgnoreCase(check);
  }
}
