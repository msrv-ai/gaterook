package ai.msrv.gaterook.connectors.influxdb;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the InfluxDB connector (docs/influxdb.md): four reads, and a write a person approves. */
final class InfluxTools {

  static final String LIST_DATABASES = "influxdb_list_databases";
  static final String LIST_MEASUREMENTS = "influxdb_list_measurements";
  static final String DESCRIBE = "influxdb_describe";
  static final String QUERY = "influxdb_query";
  static final String WRITE = "influxdb_write";

  private InfluxTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_DATABASES, "List InfluxDB databases",
            "Databases agents may use (buckets on InfluxDB 2.x).", ResultShape.JSON),
        read(schemas, LIST_MEASUREMENTS, "List measurements of a database",
            "Measurements of one database (tables on InfluxDB 3). Pages with nextCursor.", ResultShape.JSON),
        read(schemas, DESCRIBE, "Describe a measurement",
            "Tags and fields with their types of one measurement.", ResultShape.JSON),
        read(schemas, QUERY, "Run an InfluxDB query",
            "One read: SQL on InfluxDB 3, or InfluxQL SELECT and SHOW. GateRook reads each statement first and runs "
                + "only reads; the connection caps rows and size. Flux isn't supported.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(WRITE)
            .title("Write points (needs approval)")
            .description("Line protocol for one database. You approve the exact points; a point with the same "
                + "measurement, tags and timestamp as an existing one replaces its fields. Never retried.")
            .inputSchema(schemas.load(WRITE + ".input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .build());
  }

  private static ToolDefinition read(SchemaResources schemas, String name, String title, String description,
      ResultShape shape) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(shape)
        .build();
  }
}
