package com.weavo.analysis;

import com.weavo.siteaccess.RedisHostRequestCoordinator;
import com.weavo.siteaccess.SiteAccess;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SiteAccessConfiguration {
  @Bean(destroyMethod = "shutdown")
  RedisHostRequestCoordinator hostRequestCoordinator(
      @Value("${WEAVO_SITE_REDIS_URL:redis://127.0.0.1:16379}") String redisUrl,
      @Value("${WEAVO_SITE_DB_URL:jdbc:postgresql://127.0.0.1:15432/weavo}") String dbUrl,
      @Value("${WEAVO_SITE_DB_USER:weavo}") String dbUser,
      @Value("${WEAVO_SITE_DB_PASSWORD:}") String dbPassword) {
    return new RedisHostRequestCoordinator(URI.create(redisUrl), dbUrl, dbUser, dbPassword);
  }

  @Bean
  SiteAccess siteAccess(RedisHostRequestCoordinator coordinator) {
    return new SiteAccess(coordinator);
  }
}
