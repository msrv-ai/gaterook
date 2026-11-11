package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a person sees before an Azure call runs: for {@code azure_api_change} the HTTP request exactly as it goes to
 * Resource Manager (method, URL with every parameter, the masked authorization, the JSON body), for the read tools
 * the Resource Graph query or the Azure Monitor request with absolute times. The approved call runs the stored
 * payload, not the arguments evaluated again later.
 */
final class Actions {

  private Actions() {}

  /**
   * The exact Resource Manager request of {@code azure_api_read} or {@code azure_api_change}.
   *
   * @param base the Resource Manager base URL of the connection's cloud (or its test endpoint)
   */
  static ActionPreview call(CallRequest r, Settings s, ConnectionConfig cfg, URI base) {
    StringBuilder text = new StringBuilder();
    String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
        : base.toString();
    StringBuilder q = new StringBuilder();
    for (Map.Entry<String, String> e : r.parameters().entrySet()) {
      q.append(q.isEmpty() ? '?' : '&').append(Arm.encode(e.getKey())).append('=').append(Arm.encode(e.getValue()));
    }
    text.append(r.method()).append(' ').append(root).append(r.encodedPath()).append(q).append('\n');
    text.append("Authorization: Bearer ‹masked›\n");
    if (r.body() != null) {
      text.append("Content-Type: application/json\n\n").append(ResourceGraph.pretty(r.body()));
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Subscription", r.path().subscription()));
    if (r.path().resourceGroup() != null) {
      fields.add(new ActionPreview.Field("Resource group", r.path().resourceGroup()));
    }
    if (r.path().isResource()) {
      fields.add(new ActionPreview.Field("Resource", r.path().type() + " " + r.path().name()));
    }
    fields.add(new ActionPreview.Field("API version", r.apiVersion()));
    List<String> warnings = new ArrayList<>();
    if (r.method().equals("DELETE")) {
      warnings.add("DELETE removes the resource; Azure can't undo it.");
    }
    if (r.method().equals("PUT")) {
      warnings.add("PUT replaces the whole resource with the body: properties left out are reset.");
    }
    String effect = effect(r);
    if (effect != null) {
      warnings.add(effect);
      fields.add(new ActionPreview.Field("Action", r.path().segments().getLast()));
    }
    String summary = r.tool() + " on " + cfg.name() + ": " + r.method() + " " + r.rawPath();
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + s.target(),
        cut(summary))
        .withFields(fields)
        .withWarnings(warnings)
        .withFacets(Map.of("provider", "azure", "tool", r.tool(), "method", r.method()))
        .withPayload(r.payload());
  }

  /**
   * What a common action does to what serves traffic (the write cards of the research: restart, stop, swap, revision
   * traffic), for the approval card; {@code null} for other requests.
   */
  static String effect(CallRequest r) {
    if (!r.method().equals("POST") || r.path().namespaces().isEmpty()) {
      return null;
    }
    String ns = r.path().namespaces().getLast();
    String type = r.path().type() == null ? "" : r.path().type();
    List<String> seg = r.path().segments();
    String parent = seg.size() >= 3 ? seg.get(seg.size() - 3).toLowerCase(java.util.Locale.ROOT) : "";
    String what = r.path().name() == null ? "the resource" : r.path().name();
    for (int i = 0; i + 1 < seg.size() - 1; i++) {
      if (seg.get(i).equalsIgnoreCase("slots") && ns.equals("microsoft.web")) {
        what = what + " (slot " + seg.get(i + 1) + ")";
      }
    }
    return switch (ns + " " + r.path().last()) {
      case "microsoft.web restart" -> "Restarts " + what + " on every instance: requests in flight fail until it "
          + "has started again.";
      case "microsoft.web stop" -> "Stops " + what + ": it answers 403 (site stopped) until someone starts it.";
      case "microsoft.web start" -> "Starts " + what + ": it serves requests again.";
      case "microsoft.web slotsswap" -> "Swaps the slots of " + what + ": the target slot's traffic moves to the "
          + "other slot after warm-up. Swap back the same way.";
      case "microsoft.web applyslotconfig" -> "Applies the source slot's settings to the target slot (the first "
          + "phase of a swap with preview); the target slot restarts.";
      case "microsoft.web resetslotconfig" -> "Cancels a swap with preview: the slot gets its own settings back and "
          + "restarts.";
      case "microsoft.web syncfunctiontriggers" -> "Syncs " + what + "'s function triggers with the platform.";
      case "microsoft.app restart" -> parent.equals("revisions") ? "Restarts every replica of revision "
          + seg.get(seg.size() - 2) + "." : "Restarts " + what + ".";
      case "microsoft.app activate" -> "Activates revision " + seg.get(seg.size() - 2) + ": it gets replicas again "
          + "and serves its traffic weight.";
      case "microsoft.app deactivate" -> "Deactivates revision " + seg.get(seg.size() - 2) + ": its replicas are "
          + "removed and it serves no traffic.";
      case "microsoft.app start" -> type.equals("microsoft.app/jobs") ? "Starts a new execution of job " + what
          + "." : "Starts " + what + ".";
      case "microsoft.app stop" -> parent.equals("executions") ? "Stops execution " + seg.get(seg.size() - 2)
          + " of job " + what + "." : "Stops " + what + ": it serves no traffic until started.";
      case "microsoft.containerservice runcommand" -> "Runs the command inside cluster " + what + " with the "
          + "cluster's command rights (kubectl as the run-command identity).";
      case "microsoft.containerservice stop" -> "Stops cluster " + what + ": its nodes and workloads stop.";
      case "microsoft.compute restart" -> "Restarts virtual machine " + what + ".";
      case "microsoft.compute poweroff", "microsoft.compute deallocate" -> "Stops virtual machine " + what
          + (r.path().last().equals("deallocate") ? " and releases its compute (its dynamic public IP may change)."
              : " (it is still billed for compute).");
      default -> null;
    };
  }

  /**
   * An {@code azure_storage_read} or {@code azure_storage_change} call: for a change the exact request (method, the
   * account's own URL, the headers that decide what happens, the uploaded text), for a read what it lists or reads.
   */
  static ActionPreview storage(StorageRequest r, Settings s, ConnectionConfig cfg) {
    String host = s.credentials().cloud().storageHost(r.account().name(), r.action().service);
    String url = "https://" + host + r.encodedPath();
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Storage account", r.account().rawId() != null ? r.account().rawId()
        : r.account().name() + " (subscription " + r.account().subscription() + ")"));
    fields.add(new ActionPreview.Field(cap(r.action().containerField()), r.container()));
    if (!r.item().isEmpty()) {
      fields.add(new ActionPreview.Field(r.action().service.equals("blob") ? "Blob" : "Path", r.item()));
    }
    if (r.read()) {
      List<String> lines = new ArrayList<>();
      lines.add(r.tool() + " · " + cfg.name() + " (Azure · " + s.target() + " · " + cfg.env().id() + ")");
      lines.add(r.action().id + " " + url);
      if (r.prefix() != null) {
        lines.add("prefix: " + r.prefix());
      }
      if (r.filter() != null) {
        lines.add("$filter: " + r.filter());
      }
      if (!r.select().isEmpty()) {
        lines.add("$select: " + String.join(",", r.select()));
      }
      if (r.maxBytes() > 0) {
        lines.add((r.action() == StorageRequest.Action.PEEK_MESSAGES ? "bytes per message: " : "bytes from "
            + r.offset() + ": ") + r.maxBytes());
      }
      if (r.limit() > 0) {
        lines.add("items: up to " + r.limit());
      }
      if (r.cursor() != null) {
        lines.add("continues from the cursor of the previous call");
      }
      return preview(r.tool(), lines, cfg, s, r.action().id + " " + r.where(), r.payload(), fields);
    }
    StringBuilder text = new StringBuilder();
    List<String> warnings = new ArrayList<>();
    if (r.action() == StorageRequest.Action.UPLOAD_BLOB) {
      text.append("PUT ").append(url).append('\n');
      text.append("Authorization: Bearer ‹masked›\n");
      text.append("x-ms-version: ").append(Storage.VERSION).append('\n');
      text.append("x-ms-blob-type: BlockBlob\n");
      text.append("Content-Type: ").append(r.contentType()).append('\n');
      if (!r.overwrite()) {
        text.append("If-None-Match: *\n");
      }
      text.append('\n').append(r.content());
      fields.add(new ActionPreview.Field("Size", r.bodyBytes() + " bytes"));
      fields.add(new ActionPreview.Field("SHA-256", sha256(r.content())));
      warnings.add(r.overwrite() ? "Replaces the blob if it exists: its current content is gone unless the account "
          + "keeps versions or soft-deleted blobs." : "Fails if the blob exists (If-None-Match: *).");
    } else {
      text.append("DELETE ").append(url).append('\n');
      text.append("Authorization: Bearer ‹masked›\n");
      text.append("x-ms-version: ").append(Storage.VERSION).append('\n');
      if (r.deleteSnapshots()) {
        text.append("x-ms-delete-snapshots: include\n");
      }
      warnings.add("Deletes the blob" + (r.deleteSnapshots() ? " and its snapshots" : "") + "; only the account's "
          + "soft delete, if it is on, can bring it back.");
    }
    String summary = r.tool() + " on " + cfg.name() + ": " + r.action().id + " " + r.where();
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + host,
        cut(summary))
        .withFields(fields)
        .withWarnings(warnings)
        .withFacets(Map.of("provider", "azure", "tool", r.tool(), "action", r.action().id))
        .withPayload(r.payload());
  }

  /** A dead-letter peek, or the exact messages a resubmit sends again and the requests it makes. */
  static ActionPreview serviceBus(ServiceBusRequest r, Settings s, ConnectionConfig cfg) {
    String host = s.credentials().cloud().serviceBusHost(r.namespace().name());
    String dlq = "https://" + host + r.entityPath() + "/$DeadLetterQueue/messages";
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Namespace", r.namespace().rawId() != null ? r.namespace().rawId()
        : r.namespace().name() + " (subscription " + r.namespace().subscription() + ")"));
    fields.add(new ActionPreview.Field("Entity", r.entityText()));
    if (r.read()) {
      List<String> lines = new ArrayList<>();
      lines.add(r.tool() + " · " + cfg.name() + " (Azure · " + s.target() + " · " + cfg.env().id() + ")");
      lines.add("peek-lock and unlock up to " + r.count() + " messages: POST " + dlq + "/head, then PUT …/<id>/<lock>");
      lines.add("bytes per message: " + r.maxBytes());
      return preview(r.tool(), lines, cfg, s, "peek dead letters of " + r.where(), r.payload(), fields);
    }
    fields.add(new ActionPreview.Field("Sequence numbers", String.join(", ", r.numbersText())));
    fields.add(new ActionPreview.Field("Looks through", "up to " + r.scan() + " dead-lettered messages"));
    String target = "https://" + host + r.sendPath() + "/messages";
    StringBuilder text = new StringBuilder();
    text.append("For each dead-lettered message of ").append(r.where()).append(" with sequence number ")
        .append(String.join(", ", r.numbersText())).append(":\n");
    text.append("POST ").append(dlq).append("/head            (lock it)\n");
    text.append("POST ").append(target).append("   (send a copy: body, content type, message ID, properties)\n");
    text.append("DELETE ").append(dlq).append("/<id>/<lock>   (complete the dead letter)\n");
    text.append("Every other locked message: PUT ").append(dlq).append("/<id>/<lock> (unlock)\n");
    text.append("Authorization: Bearer ‹masked›\n");
    List<String> warnings = new ArrayList<>();
    warnings.add(r.queue() != null ? "Consumers of queue " + r.queue() + " receive these messages again."
        : "The copies go to topic " + r.topic() + ": every subscription whose rules match receives them again, not "
            + "only " + r.topicSubscription() + ".");
    warnings.add("With duplicate detection on, a copy whose message ID was sent within the detection window is "
        + "dropped.");
    String summary = r.tool() + " on " + cfg.name() + ": resubmit " + r.sequenceNumbers().size() + " of " + r.where();
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + host,
        cut(summary))
        .withFields(fields)
        .withWarnings(warnings)
        .withFacets(Map.of("provider", "azure", "tool", r.tool()))
        .withPayload(r.payload());
  }

  static ActionPreview keyVault(KeyVaultRequest r, Settings s, ConnectionConfig cfg) {
    String host = s.credentials().cloud().keyVaultHost(r.vault().name());
    List<String> lines = new ArrayList<>();
    lines.add(KeyVaultRequest.TOOL + " · " + cfg.name() + " (Azure · " + s.target() + " · " + cfg.env().id() + ")");
    lines.add("GET https://" + host + r.path() + "?api-version=" + KeyVault.API + "&maxresults=" + r.limit());
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview(KeyVaultRequest.TOOL, lines, cfg, s, r.action().id + " of " + r.vault().name()
        + (r.name() == null ? "" : "/" + r.name()), r.payload(), List.of(new ActionPreview.Field("Vault",
        r.vault().rawId() != null ? r.vault().rawId() : r.vault().name())));
  }

  static String sha256(String text) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String cap(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  static ActionPreview describe(DescribeRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("azure_describe · " + cfg.name() + " (Azure · " + s.target() + " · " + cfg.env().id() + ")");
    lines.add("POST /providers/Microsoft.ResourceGraph/resources?api-version=" + ResourceGraph.API_VERSION);
    lines.add("subscriptions: " + String.join(", ", r.subscriptions()));
    lines.add("query: " + r.effectiveQuery());
    lines.add("$top=" + r.limit() + " resultFormat=objectArray");
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview("azure_describe", lines, cfg, s, r.get() ? r.id() : r.query(), r.payload(),
        List.of(new ActionPreview.Field("Subscriptions", String.join(", ", r.subscriptions())),
            new ActionPreview.Field("Limit", r.limit() + " rows")));
  }

  static ActionPreview monitor(MonitorRequest r, Settings s, ConnectionConfig cfg) {
    List<String> lines = new ArrayList<>();
    lines.add("azure_monitor · " + cfg.name() + " (Azure · " + r.subscription() + " · " + cfg.env().id() + ")");
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Subscription", r.subscription()));
    switch (r.action()) {
      case QUERY -> {
        lines.add("GET " + r.rawResource() + "/providers/Microsoft.Insights/metrics?api-version="
            + Monitor.METRICS_API);
        lines.add("metricnames: " + String.join(", ", r.metrics()) + " · aggregation " + String.join(", ",
            r.aggregations()) + " · interval " + r.interval());
        lines.add("timespan: " + r.from() + "/" + r.to());
        if (r.filter() != null) {
          lines.add("$filter: " + r.filter() + (r.top() > 0 ? " · top " + r.top() : ""));
        }
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
      }
      case LIST_METRICS -> lines.add("GET " + r.rawResource() + "/providers/Microsoft.Insights/metricDefinitions"
          + "?api-version=" + Monitor.METRICS_API);
      case ALERTS -> {
        lines.add("GET /subscriptions/" + r.subscription() + "/providers/Microsoft.AlertsManagement/alerts"
            + "?api-version=" + Monitor.ALERTS_API);
        lines.add("monitorCondition=" + r.condition() + (r.timeRange() != null ? " timeRange=" + r.timeRange()
            : " customTimeRange=" + r.from() + "/" + r.to()) + (r.state() == null ? "" : " alertState=" + r.state())
            + (r.severity() == null ? "" : " severity=" + r.severity()));
      }
      case ACTIVITY_LOG -> {
        lines.add("GET /subscriptions/" + r.subscription() + "/providers/Microsoft.Insights/eventtypes/management/"
            + "values?api-version=" + Monitor.ACTIVITY_API);
        lines.add("eventTimestamp " + r.from() + " → " + r.to() + (r.resourceGroup() == null ? ""
            : " · resourceGroupName " + r.resourceGroup()) + (r.rawResource() == null ? ""
            : " · resourceUri " + r.rawResource()));
        fields.add(new ActionPreview.Field("Range", r.from() + " → " + r.to()));
      }
      case HEALTH -> lines.add("GET " + (r.rawResource() != null ? r.rawResource()
          + "/providers/Microsoft.ResourceHealth/availabilityStatuses/current" : "/subscriptions/" + r.subscription()
          + "/providers/Microsoft.ResourceHealth/availabilityStatuses") + "?api-version=" + Monitor.HEALTH_API);
    }
    lines.add("items: up to " + r.limit());
    if (r.cursor() != null) {
      lines.add("continues from the cursor of the previous call");
    }
    return preview("azure_monitor", lines, cfg, s, r.action().id + (r.rawResource() == null ? ""
        : " " + r.rawResource()), r.payload(), fields);
  }

  private static ActionPreview preview(String tool, List<String> lines, ConnectionConfig cfg, Settings s, String what,
      JsonNode payload, List<ActionPreview.Field> fields) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", String.join("\n", lines), cfg.name() + " · "
        + s.target(), cut(tool + " on " + cfg.name() + ": " + what))
        .withFields(fields)
        .withFacets(Map.of("provider", "azure", "tool", tool))
        .withPayload(payload);
  }

  private static String cut(String summary) {
    return summary.length() > 120 ? summary.substring(0, 119) + "…" : summary;
  }
}
