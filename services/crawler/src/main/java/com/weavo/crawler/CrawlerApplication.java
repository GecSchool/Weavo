package com.weavo.crawler;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(WorkerSettings.class)
public class CrawlerApplication {
  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(CrawlerApplication.class, args);
    for (String arg : args) {
      if (arg.equals("--check") || arg.equals("--check=true")) {
        context.close();
        return;
      }
    }
  }

  @Bean
  ApplicationRunner resourceCheckRunner(WorkerSettings settings) {
    return (ApplicationArguments args) -> {
      if (args.containsOption("check") && !"false".equals(firstValue(args))) {
        new AwsResourceChecker(settings).check();
      }
    };
  }

  private static String firstValue(ApplicationArguments args) {
    var values = args.getOptionValues("check");
    return values == null || values.isEmpty() ? "true" : values.get(0);
  }
}
