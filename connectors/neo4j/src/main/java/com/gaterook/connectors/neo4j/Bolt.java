package com.gaterook.connectors.neo4j;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;

/**
 * What the connector asks of a Neo4j server: explicit transactions and their results. {@link DriverBolt} is the
 * official driver behind it; tests stand in for a server with a fake. Failures are the driver's own exceptions
 * ({@code org.neo4j.driver.exceptions}), which {@link Neo4jErrors} turns into the connector's.
 */
interface Bolt {

  /** Where calls get their server: one driver per version of a connection and its sign-in. */
  interface Source extends AutoCloseable {

    /** The connection's client, opened if needed; closing the lease ends the call's claim on it. */
    Lease lease(ConnectionConfig cfg, Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx)
        throws ConnectorException;

    /** A client of its own for Test connection: nothing is kept, the lease closes it. */
    Lease fresh(Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx) throws ConnectorException;

    /** The connection changed or went: its client closes once its calls end. */
    void evict(String connectionId);

    @Override
    void close();
  }

  /** One call's use of a client. */
  interface Lease extends AutoCloseable {

    Client client();

    @Override
    void close();
  }

  /** One connection's server, as one call uses it. */
  interface Client {

    /**
     * Begins an explicit transaction: never retried, rolled back unless committed.
     *
     * @param mode      READ: the server refuses writes in it; WRITE: the cluster's leader
     * @param database  the connection's database ({@code system} only for Test connection's own commands)
     * @param timeout   the server stops the transaction after this long
     * @param metadata  shown by {@code SHOW TRANSACTIONS} and in the query log
     * @param fetchSize records fetched per batch
     */
    Tx begin(AccessMode mode, String database, Duration timeout, Map<String, Object> metadata, int fetchSize);
  }

  /** An explicit transaction. */
  interface Tx extends AutoCloseable {

    Run run(String cypher, Map<String, Object> parameters);

    void commit();

    /** Rolls back unless committed; closes the session. */
    @Override
    void close();
  }

  /** The result of one statement: its records as they arrive, then its summary. */
  interface Run {

    List<String> keys();

    boolean hasNext();

    Record next();

    /** Discards the records not read yet and returns the summary. */
    Summary consume();
  }

  /**
   * What the server says about a statement it ran or planned.
   *
   * @param queryType {@code r}, {@code rw}, {@code w}, {@code s}, or {@code null} when the server says nothing
   * @param counters  the server's counters that aren't zero ({@code nodesCreated} …)
   * @param plan      the plan of an {@code EXPLAIN}, or {@code null}
   * @param agent     the server's agent ({@code Neo4j/5.26.31}), or {@code null}
   * @param protocol  the Bolt version ({@code 5.8}), or {@code null}
   */
  record Summary(String queryType, Map<String, Integer> counters, Plan plan, List<Note> notifications, String agent,
      String protocol) {

    boolean updates() {
      return !counters.isEmpty();
    }
  }

  /**
   * One operator of a plan.
   *
   * @param estimatedRows the planner's estimate, or {@code null}
   * @param details       the operator's details ({@code n:Person}), or {@code null}
   */
  record Plan(String operator, Double estimatedRows, String details, List<Plan> children) {}

  /** A notification of the server: a warning of the planner (a cartesian product, an unknown label), a deprecation. */
  record Note(String code, String title, String description, String severity) {}
}
