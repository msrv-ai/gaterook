package ai.msrv.gaterook.core.audit.export;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rows on their way to a destination and the head that vouches for them.
 *
 * @param key     what names the batch at the destination, the same on every attempt: {@code <from>-<to>} of the
 *                cursor range for a scheduled batch (an S3 object written again is the same object, a BigQuery insert
 *                id the same id), {@code activity-<millis>} for calls sent from Activity
 * @param firstAt the time of the first row: the date folder of an S3 object
 */
public record Batch(String key, List<ObjectNode> rows, ObjectNode head, Instant firstAt) {

  public Batch {
    Objects.requireNonNull(key, "key");
    rows = List.copyOf(rows);
    Objects.requireNonNull(head, "head");
    Objects.requireNonNull(firstAt, "firstAt");
  }

  /** The rows, then the head. */
  public List<ObjectNode> records() {
    List<ObjectNode> all = new ArrayList<>(rows.size() + 1);
    all.addAll(rows);
    all.add(head);
    return all;
  }
}
