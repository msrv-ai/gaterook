package com.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoInternalException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketClosedException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoSocketWriteException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.WriteConcernError;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/**
 * What a failed update says (report 05 §5): once its request may have reached the server, the update may have
 * changed documents, so the call is {@code OUTCOME_UNKNOWN} — the core then marks an approved request so and never
 * runs it again. A failure before the request leaves the client, and an error the server answered before changing
 * anything, stay what they were. Reads are never an unknown outcome.
 */
class MongoUpdateOutcomeTest {

  private static final MongoSettings SETTINGS = MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://127.0.0.1:1/orders"));
  private static final ServerAddress SERVER = new ServerAddress("127.0.0.1", 1);

  private static ConnectorException update(MongoException e, boolean many) {
    return MongoErrors.update(e, "orders-db", SETTINGS, null, many);
  }

  private static BsonDocument answer(int code, String message) {
    return new BsonDocument("ok", new BsonInt32(0)).append("code", new BsonInt32(code))
        .append("errmsg", new BsonString(message));
  }

  @Test
  void anUpdateWhoseRequestMayHaveReachedTheServerHasAnUnknownOutcome() {
    List<MongoException> afterSending = List.of(
        new MongoSocketReadException("Prematurely reached end of stream", SERVER),
        new MongoSocketReadTimeoutException("Timeout while receiving message", SERVER, new SocketTimeoutException()),
        new MongoSocketClosedException("Cannot read from a closed stream", SERVER),
        new MongoInterruptedException("Interrupted while receiving message", new InterruptedException()),
        new MongoWriteConcernException(new WriteConcernError(64, "WriteConcernFailed", "waiting for replication timed "
            + "out", new BsonDocument()), SERVER),
        new MongoNotPrimaryException(answer(10107, "not primary"), SERVER),
        new MongoNodeIsRecoveringException(answer(11602, "operation was interrupted"), SERVER),
        new MongoCommandException(answer(11601, "operation was interrupted"), SERVER),
        new MongoExecutionTimeoutException(50, "operation exceeded time limit"),
        new MongoInternalException("Unexpected reply"));
    for (boolean many : List.of(false, true)) {
      for (MongoException e : afterSending) {
        ConnectorException out = update(e, many);
        assertThat(out.kind()).as(e.getClass().getSimpleName()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
        assertThat(out.getMessage()).as(e.getClass().getSimpleName()).startsWith("Outcome unknown: ")
            .contains("orders-db").endsWith("It may have changed documents; read them before retrying.");
        assertThat(out.countsForCircuitBreaker()).isFalse();
      }
    }
  }

  @Test
  void anUpdateManyThatStoppedAtAWriteErrorMayHaveChangedTheDocumentsBefore() {
    MongoWriteException partway = new MongoWriteException(new WriteError(14, "Cannot apply $inc to a value of "
        + "non-numeric type", new BsonDocument()), SERVER);

    ConnectorException many = update(partway, true);
    ConnectorException one = update(partway, false);

    assertThat(many.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
    assertThat(many.getMessage()).contains("Cannot apply $inc to a value of non-numeric type (code 14)")
        .contains("Documents it changed before the error stay changed.");
    assertThat(one.kind()).as("one document changes atomically or not at all")
        .isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
    assertThat(one.getMessage()).startsWith("MongoDB refused the query on orders-db: Cannot apply $inc");
  }

  @Test
  void aFailureBeforeTheRequestLeftTheClientOrARefusalOfTheServerStaysWhatItWas() {
    List<MongoException> neverApplied = List.of(
        new MongoSocketOpenException("Exception opening socket", SERVER, new ConnectException("Connection refused")),
        new MongoSocketWriteException("Exception sending message", SERVER, new IOException("Broken pipe")),
        new MongoTimeoutException("Timed out while waiting for a server that matches WritableServerSelector"),
        new MongoSecurityException(MongoCredential.createCredential("gw_rw", "admin", new char[0]), "Exception "
            + "authenticating"),
        new MongoCommandException(answer(13, "not authorized on orders to execute command"), SERVER));
    for (boolean many : List.of(false, true)) {
      for (MongoException e : neverApplied) {
        ConnectorException out = update(e, many);
        assertThat(out.kind()).as(e.getClass().getSimpleName()).isNotEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
        assertThat(out.getMessage()).isEqualTo(MongoErrors.call(e, "orders-db", SETTINGS, null).getMessage());
      }
    }
    // The call's own deadline stays a timeout; its text already says the update may have applied, and the core treats
    // a timed-out approved call as one whose outcome is unknown.
    ConnectorException deadline = update(new MongoOperationTimeoutException("Timeout while receiving message"), true);
    assertThat(deadline.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
    assertThat(deadline.getMessage()).contains("An update may have applied changes before the timeout");
  }

  @Test
  void aReadThatLostItsConnectionIsStillUnavailableNotUnknown() {
    ConnectorException read = MongoErrors.call(new MongoSocketReadException("Prematurely reached end of stream",
        SERVER), "orders-db", SETTINGS, null);

    assertThat(read.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
  }
}
