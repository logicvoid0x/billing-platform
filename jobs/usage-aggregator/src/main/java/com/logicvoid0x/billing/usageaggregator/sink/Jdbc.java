package com.logicvoid0x.billing.usageaggregator.sink;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Where the metering schema lives. Serializable so Flink ships it to the committer.
 *
 * @param url JDBC URL of the billing database
 * @param user database user
 * @param password database password
 */
public record Jdbc(String url, String user, String password) implements Serializable {
  /** Validates the settings. */
  public Jdbc {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(password, "password");
  }

  /** Opens a connection. */
  public Connection connect() throws SQLException {
    return DriverManager.getConnection(url, user, password);
  }

  @Override
  public String toString() {
    return "Jdbc[" + url + ", user=" + user + "]"; // never print the password
  }
}
