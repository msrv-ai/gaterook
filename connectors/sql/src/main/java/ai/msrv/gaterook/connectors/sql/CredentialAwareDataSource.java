package ai.msrv.gaterook.connectors.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * The data source under a connection's pool (report 04 §6.7): Hikari asks it for every physical connection, and it
 * connects with the login it holds now. A new login (Replace, Rotate, a new Vault lease) replaces the old one, whose
 * password is zeroed; connections opened with it stay until Hikari evicts them.
 *
 * <p>The password is a {@code char[]} that lives as long as the pool. Each connect makes a short-lived String of it,
 * because that is what JDBC drivers take; it never goes into the pool's configuration, its name or a log.
 */
final class CredentialAwareDataSource implements DataSource {

  private final Driver driver;
  private final String url;
  private final Properties base;
  private volatile Credentials current;
  private volatile int loginTimeoutSeconds;

  CredentialAwareDataSource(Driver driver, String url, Properties base, Credentials credentials) {
    this.driver = driver;
    this.url = url;
    this.base = base;
    this.current = credentials;
  }

  /** The login to use from now on; returns whether it differs from the one before. */
  synchronized boolean update(Credentials next) {
    Credentials before = current;
    if (before.equals(next)) {
      next.close();
      return false;
    }
    current = next;
    before.close();
    return true;
  }

  Credentials current() {
    return current;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Properties p = new Properties();
    p.putAll(base);
    synchronized (this) {
      // Under the lock of update(): the password is never read while it is being zeroed.
      Credentials c = current;
      if (c.user() != null) {
        p.setProperty("user", c.user());
      }
      if (c.password().length > 0) {
        p.setProperty("password", new String(c.password()));
      }
    }
    Connection connection = driver.connect(url, p);
    if (connection == null) {
      throw new SQLException("The driver doesn't take this URL.", "08001");
    }
    return connection;
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLFeatureNotSupportedException("The login comes from the connection's secret");
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    // No driver logging: it could carry statements with data.
  }

  @Override
  public void setLoginTimeout(int seconds) {
    loginTimeoutSeconds = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeoutSeconds;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  /** Zeroes the password; the pool is closed. */
  synchronized void close() {
    current.close();
  }

  @Override
  public String toString() {
    return "CredentialAwareDataSource[" + url + "]";
  }
}
