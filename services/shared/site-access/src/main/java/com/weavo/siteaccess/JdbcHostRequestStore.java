package com.weavo.siteaccess;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

/** Durable record of requests whose transport has not confirmed completion. */
final class JdbcHostRequestStore {
  private static final String ACQUIRE =
      """
      INSERT INTO weavo_site_access.host_request (host, owner) VALUES (?, ?)
      ON CONFLICT (host) DO UPDATE SET owner = EXCLUDED.owner
      WHERE host_request.owner IS NULL AND host_request.not_before <= clock_timestamp()
      """;
  private static final String RELEASE =
      """
      UPDATE weavo_site_access.host_request
      SET owner = NULL, not_before = clock_timestamp() + interval '1 second'
      WHERE host = ? AND owner = ?
      """;

  private final String url;
  private final Properties connectionProperties = new Properties();

  JdbcHostRequestStore(String url, String user, String password) {
    this.url = url;
    connectionProperties.setProperty("user", user);
    connectionProperties.setProperty("password", password);
    connectionProperties.setProperty("connectTimeout", "1");
    connectionProperties.setProperty("socketTimeout", "2");
    connectionProperties.setProperty(
        "options", "-c statement_timeout=750ms -c lock_timeout=500ms -c synchronous_commit=on");
  }

  boolean acquire(String host, UUID token) throws SQLException {
    return update(ACQUIRE, host, token);
  }

  void release(String host, UUID token) throws SQLException {
    update(RELEASE, host, token);
  }

  private boolean update(String sql, String host, UUID token) throws SQLException {
    try (var connection = DriverManager.getConnection(url, connectionProperties);
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, host);
      statement.setObject(2, token);
      return statement.executeUpdate() == 1;
    }
  }
}
