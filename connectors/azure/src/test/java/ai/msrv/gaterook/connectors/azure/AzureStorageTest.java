package ai.msrv.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code azure_storage_read} and {@code azure_storage_change} against a fake Resource Manager and a fake storage
 * account: the account is checked through Resource Manager first, then Blob, Queue, Table and File requests go to the
 * account's own host with a Storage token; uploads and deletes run only as approved.
 */
class AzureStorageTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String ACCOUNT = "/subscriptions/" + SUB + "/resourceGroups/stage-data/providers/"
      + "Microsoft.Storage/storageAccounts/acmestage";
  private static final String BLOB = "/acmestage.blob.core.windows.net";
  private FakeAzure azure;
  private AzureConnector connector;
  private final ConnectionConfig cfg = tested(Configs.stage());

  @BeforeEach
  void start() throws Exception {
    azure = new FakeAzure();
    connector = new AzureConnector(azure.cloud, Clock.systemUTC());
    azure.on("GET " + ACCOUNT, r -> FakeAzure.json(200, account("acmestage", "westeurope",
        "https://acmestage.blob.core.windows.net/")));
  }

  @AfterEach
  void stop() {
    connector.close();
    azure.close();
  }

  private static String account(String name, String location, String blobEndpoint) {
    return "{\"id\":\"/subscriptions/" + SUB + "/resourceGroups/stage-data/providers/Microsoft.Storage/"
        + "storageAccounts/" + name + "\",\"name\":\"" + name + "\",\"type\":\"Microsoft.Storage/storageAccounts\","
        + "\"location\":\"" + location + "\",\"kind\":\"StorageV2\",\"properties\":{\"primaryEndpoints\":{\"blob\":\""
        + blobEndpoint + "\",\"queue\":\"https://" + name + ".queue.core.windows.net/\",\"table\":\"https://" + name
        + ".table.core.windows.net/\",\"file\":\"https://" + name + ".file.core.windows.net/\"}}}";
  }

  private static ConnectionConfig tested(ConnectionConfig c) {
    return new ConnectionConfig(c.id(), c.name(), c.type(), c.env(), c.version(), c.config(), c.secretFields(),
        TestCtx.JSON.createObjectNode().put("tenant", FakeAzure.TENANT));
  }

  private ToolResult read(String args) throws ConnectorException {
    return read(args, Configs.policy(Env.STAGE));
  }

  private ToolResult read(String args, PolicyView policy) throws ConnectorException {
    ToolCall call = Configs.call(StorageRequest.READ, cfg.name(), args);
    Classification k = connector.classify(call, cfg, policy);
    assertThat(k.kind()).as(k.reason()).isEqualTo(Classification.Kind.READ);
    return OutputSchema.storageRead(connector.execute(call, cfg, Configs.secrets(), TestCtx.of()));
  }

  private Classification classify(String tool, String args, PolicyView policy) {
    return connector.classify(Configs.call(tool, cfg.name(), args), cfg, policy);
  }

  // ── Blobs ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void listBlobsChecksTheAccountThenListsWithAStorageToken() throws Exception {
    azure.on("GET " + BLOB + "/uploads", r -> FakeAzure.xml(200, "<EnumerationResults ContainerName=\"uploads\">"
        + "<Prefix>reports/</Prefix><MaxResults>50</MaxResults><Delimiter>/</Delimiter><Blobs>"
        + "<Blob><Name>reports/2026-q3.csv</Name><Properties><Last-Modified>Mon, 09 Nov 2026 10:00:00 GMT"
        + "</Last-Modified><Content-Length>2048</Content-Length><Content-Type>text/csv</Content-Type>"
        + "<BlobType>BlockBlob</BlobType><AccessTier>Hot</AccessTier></Properties></Blob>"
        + "<Blob><Name>reports/a&amp;b.json</Name><Properties><Content-Length>12</Content-Length></Properties></Blob>"
        + "<BlobPrefix><Name>reports/archive/</Name></BlobPrefix></Blobs><NextMarker>2!80!MDAwMDQ1</NextMarker>"
        + "</EnumerationResults>"));

    ToolResult r = read("{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\",\"container\":\"uploads\","
        + "\"prefix\":\"reports/\"}");

    JsonNode s = r.structuredContent();
    assertThat(s.at("/blobs/0/name").asString()).isEqualTo("reports/2026-q3.csv");
    assertThat(s.at("/blobs/0/size").asLong()).isEqualTo(2048);
    assertThat(s.at("/blobs/1/name").asString()).isEqualTo("reports/a&b.json");
    assertThat(s.at("/prefixes/0").asString()).isEqualTo("reports/archive/");
    assertThat(s.path("nextCursor").asString()).startsWith("az1.");
    assertThat(s.path("location").asString()).isEqualTo("westeurope");
    RecordedRequest sent = azure.to("GET " + BLOB + "/uploads").getFirst();
    assertThat(FakeAzure.param(sent, "comp")).isEqualTo("list");
    assertThat(FakeAzure.param(sent, "restype")).isEqualTo("container");
    assertThat(FakeAzure.param(sent, "delimiter")).isEqualTo("/");
    assertThat(FakeAzure.param(sent, "maxresults")).isEqualTo("50");
    assertThat(sent.getHeaders().get("x-ms-version")).isEqualTo(Storage.VERSION);
    assertThat(sent.getHeaders().get("Authorization")).startsWith("Bearer ");
    assertThat(azure.scopes()).contains("https://storage.azure.com/.default", "https://"
        + "management.azure.com/.default");
    assertThat(azure.to("GET " + ACCOUNT)).hasSize(1);

    read("{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\",\"container\":\"uploads\",\"prefix\":"
        + "\"reports/\",\"cursor\":\"" + s.path("nextCursor").asString() + "\"}");
    assertThat(FakeAzure.param(azure.to("GET " + BLOB + "/uploads").getLast(), "marker")).isEqualTo("2!80!MDAwMDQ1");
  }

  @Test
  void aCursorBelongsToItsListing() throws Exception {
    azure.on("GET " + BLOB + "/uploads", r -> FakeAzure.xml(200, "<EnumerationResults><Blobs/><NextMarker>m1"
        + "</NextMarker></EnumerationResults>"));
    String cursor = read("{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\",\"container\":\"uploads\"}")
        .structuredContent().path("nextCursor").asString();
    ToolCall other = Configs.call(StorageRequest.READ, cfg.name(), "{\"action\":\"list_blobs\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\",\"prefix\":\"x/\",\"cursor\":\"" + cursor + "\"}");
    connector.classify(other, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(other, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("another query");
  }

  @Test
  void readBlobReadsABoundedRangeOfText() throws Exception {
    String path = BLOB + "/uploads/reports/2026-q3.csv";
    azure.on("HEAD " + path, r -> new MockResponse.Builder().code(200).addHeader("Content-Type", "text/csv")
        .setHeader("Content-Length", "30").addHeader("x-ms-blob-type", "BlockBlob").build());
    azure.on("GET " + path, r -> new MockResponse.Builder().code(206).body("id,amount\n1,10\n2,20\n").build());

    ToolResult r = read("{\"action\":\"read_blob\",\"account\":\"" + ACCOUNT + "\",\"container\":\"uploads\","
        + "\"blob\":\"reports/2026-q3.csv\",\"maxBytes\":20}");

    JsonNode s = r.structuredContent();
    assertThat(s.path("text").asString()).isEqualTo("id,amount\n1,10\n2,20\n");
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("nextOffset").asLong()).isEqualTo(20);
    RecordedRequest get = azure.to("GET " + path).getFirst();
    assertThat(get.getHeaders().get("x-ms-range")).isEqualTo("bytes=0-19");
  }

  @Test
  void blobsThatHoldKeysOrArentTextAreNotRead() throws Exception {
    Classification env = classify(StorageRequest.READ, "{\"action\":\"read_blob\",\"account\":\"" + ACCOUNT
        + "\",\"container\":\"uploads\",\"blob\":\"app/.env\"}", Configs.policy(Env.STAGE));
    assertThat(env.ruleId()).isEqualTo("stage.cloud.object_not_allowed");
    Classification state = classify(StorageRequest.READ, "{\"action\":\"read_blob\",\"account\":\"" + ACCOUNT
        + "\",\"container\":\"tfstate\",\"blob\":\"prod.terraform.tfstate\"}", Configs.policy(Env.STAGE));
    assertThat(state.kind()).isEqualTo(Classification.Kind.DENY);

    azure.on("HEAD " + BLOB + "/uploads/logo.png", r -> new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "image/png").setHeader("Content-Length", "5120").build());
    ToolCall png = Configs.call(StorageRequest.READ, cfg.name(), "{\"action\":\"read_blob\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\",\"blob\":\"logo.png\"}");
    connector.classify(png, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(png, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("image/png").hasMessageContaining("text only");
    assertThat(azure.to("GET " + BLOB + "/uploads/logo.png")).isEmpty();
  }

  @Test
  void aMissingDataRoleIsExplained() throws Exception {
    azure.on("GET " + BLOB + "/uploads", r -> new MockResponse.Builder().code(403)
        .addHeader("x-ms-error-code", "AuthorizationPermissionMismatch").body("﻿<?xml version=\"1.0\" "
            + "encoding=\"utf-8\"?><Error><Code>AuthorizationPermissionMismatch</Code><Message>This request is not "
            + "authorized to perform this operation using this permission.\nRequestId:1</Message></Error>").build());
    ToolCall call = Configs.call(StorageRequest.READ, cfg.name(), "{\"action\":\"list_blobs\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\"}");
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("Storage Blob Data Reader")
        .hasMessageNotContaining("RequestId");
  }

  // ── Queues, tables, files ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void peekMessagesDequeuesNothing() throws Exception {
    String queue = "/acmestage.queue.core.windows.net/orders";
    String encoded = Base64.getEncoder().encodeToString("{\"order\":42}".getBytes(StandardCharsets.UTF_8));
    azure.on("GET " + queue, r -> new MockResponse.Builder().code(200)
        .addHeader("x-ms-approximate-messages-count", "3").build());
    azure.on("GET " + queue + "/messages", r -> FakeAzure.xml(200, "<QueueMessagesList><QueueMessage><MessageId>"
        + "m-1</MessageId><InsertionTime>Mon, 09 Nov 2026 10:00:00 GMT</InsertionTime><ExpirationTime>Mon, 16 Nov "
        + "2026 10:00:00 GMT</ExpirationTime><DequeueCount>2</DequeueCount><MessageText>" + encoded
        + "</MessageText></QueueMessage><QueueMessage><MessageId>m-2</MessageId><DequeueCount>0</DequeueCount>"
        + "<MessageText>plain &lt;text&gt;</MessageText></QueueMessage></QueueMessagesList>"));

    ToolResult r = read("{\"action\":\"peek_messages\",\"account\":\"" + ACCOUNT + "\",\"queue\":\"orders\","
        + "\"count\":5}");

    JsonNode s = r.structuredContent();
    assertThat(s.path("approximateMessages").asLong()).isEqualTo(3);
    assertThat(s.at("/messages/0/text").asString()).isEqualTo("{\"order\":42}");
    assertThat(s.at("/messages/0/encoding").asString()).isEqualTo("base64");
    assertThat(s.at("/messages/0/dequeueCount").asLong()).isEqualTo(2);
    assertThat(s.at("/messages/1/text").asString()).isEqualTo("plain <text>");
    RecordedRequest peek = azure.to("GET " + queue + "/messages").getFirst();
    assertThat(FakeAzure.param(peek, "peekonly")).isEqualTo("true");
    assertThat(FakeAzure.param(peek, "numofmessages")).isEqualTo("5");
    assertThat(azure.api()).noneMatch(q -> !q.getMethod().equals("GET"));
  }

  @Test
  void queryEntitiesMasksSecretsAndPagesWithItsContinuation() throws Exception {
    String table = "/acmestage.table.core.windows.net/Orders()";
    azure.on("GET " + table, r -> new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json;odata=nometadata")
        .addHeader("x-ms-continuation-NextPartitionKey", "1!12!b3JkZXJz")
        .addHeader("x-ms-continuation-NextRowKey", "1!8!NDI=")
        .body("{\"value\":[{\"PartitionKey\":\"orders\",\"RowKey\":\"41\",\"Status\":\"failed\",\"ApiKey\":"
            + "\"cnry-table-api-key-0001\"}]}").build());

    ToolResult r = read("{\"action\":\"query_entities\",\"account\":\"" + ACCOUNT + "\",\"table\":\"Orders\","
        + "\"filter\":\"PartitionKey eq 'orders'\",\"select\":[\"PartitionKey\",\"RowKey\",\"Status\",\"ApiKey\"],"
        + "\"limit\":10}");

    JsonNode s = r.structuredContent();
    assertThat(s.at("/entities/0/Status").asString()).isEqualTo("failed");
    assertThat(s.at("/entities/0/ApiKey").asString()).isEqualTo(Redactor.MASK);
    assertThat(r.content().getFirst().text()).doesNotContain("cnry-table-api-key-0001");
    RecordedRequest first = azure.to("GET " + table).getFirst();
    assertThat(FakeAzure.param(first, "$filter")).isEqualTo("PartitionKey eq 'orders'");
    assertThat(FakeAzure.param(first, "$select")).isEqualTo("PartitionKey,RowKey,Status,ApiKey");
    assertThat(FakeAzure.param(first, "$top")).isEqualTo("10");
    assertThat(first.getHeaders().get("Accept")).isEqualTo("application/json;odata=nometadata");
    assertThat(first.getHeaders().get("x-ms-version")).isEqualTo(Storage.TABLE_VERSION);

    read("{\"action\":\"query_entities\",\"account\":\"" + ACCOUNT + "\",\"table\":\"Orders\",\"filter\":"
        + "\"PartitionKey eq 'orders'\",\"select\":[\"PartitionKey\",\"RowKey\",\"Status\",\"ApiKey\"],\"limit\":10,"
        + "\"cursor\":\"" + s.path("nextCursor").asString() + "\"}");
    RecordedRequest next = azure.to("GET " + table).getLast();
    assertThat(FakeAzure.param(next, "NextPartitionKey")).isEqualTo("1!12!b3JkZXJz");
    assertThat(FakeAzure.param(next, "NextRowKey")).isEqualTo("1!8!NDI=");
  }

  @Test
  void filesAreListedAndReadWithTheBackupIntent() throws Exception {
    String share = "/acmestage.file.core.windows.net/logs";
    azure.on("GET " + share + "/app", r -> FakeAzure.xml(200, "<EnumerationResults ShareName=\"logs\" "
        + "DirectoryPath=\"app\"><Entries><Directory><Name>2026</Name></Directory><File><Name>app.log</Name>"
        + "<Properties><Content-Length>11</Content-Length></Properties></File></Entries><NextMarker/>"
        + "</EnumerationResults>"));
    azure.on("HEAD " + share + "/app/app.log", r -> new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/octet-stream").setHeader("Content-Length", "11").build());
    azure.on("GET " + share + "/app/app.log", r -> new MockResponse.Builder().code(206).body("ERROR boom\n")
        .build());

    JsonNode list = read("{\"action\":\"list_files\",\"account\":\"" + ACCOUNT + "\",\"share\":\"logs\","
        + "\"path\":\"app\"}").structuredContent();
    JsonNode file = read("{\"action\":\"read_file\",\"account\":\"" + ACCOUNT + "\",\"share\":\"logs\","
        + "\"path\":\"app/app.log\"}").structuredContent();

    assertThat(list.at("/directories/0").asString()).isEqualTo("2026");
    assertThat(list.at("/files/0/name").asString()).isEqualTo("app.log");
    assertThat(list.has("nextCursor")).isFalse();
    assertThat(file.path("text").asString()).isEqualTo("ERROR boom\n");
    for (RecordedRequest q : azure.to("GET " + share)) {
      assertThat(q.getHeaders().get("x-ms-file-request-intent")).isEqualTo("backup");
    }
    assertThat(FakeAzure.param(azure.to("GET " + share + "/app").getFirst(), "restype")).isEqualTo("directory");
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anUploadRunsOnlyAsApproved() throws Exception {
    String path = BLOB + "/uploads/notes/today.md";
    azure.on("PUT " + path, r -> new MockResponse.Builder().code(201).addHeader("ETag", "\"0x8DC\"").build());
    ToolCall call = Configs.call(StorageRequest.CHANGE, cfg.name(), "{\"action\":\"upload_blob\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\",\"blob\":\"notes/today.md\",\"content\":\"# Today\\nAll good\","
        + "\"contentType\":\"text/markdown\"}");
    Classification k = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(k.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(k.reason()).isEqualTo("Azure Storage upload_blob acmestage/uploads/notes/today.md");

    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("only after the person approves");
    assertThat(azure.to("PUT ")).isEmpty();

    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).startsWith("PUT https://acmestage.blob.core.windows.net/uploads/notes/today.md\n")
        .contains("If-None-Match: *").contains("Content-Type: text/markdown").endsWith("# Today\nAll good");
    assertThat(preview.fields()).anySatisfy(f -> assertThat(f.label()).isEqualTo("SHA-256"));
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("Fails if the blob exists"));

    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(preview.payload()));

    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(201);
    RecordedRequest put = azure.to("PUT " + path).getFirst();
    assertThat(put.getBody().utf8()).isEqualTo("# Today\nAll good");
    assertThat(put.getHeaders().get("x-ms-blob-type")).isEqualTo("BlockBlob");
    assertThat(put.getHeaders().get("If-None-Match")).isEqualTo("*");
    assertThat(put.getHeaders().get("Content-Type")).isEqualTo("text/markdown");
  }

  @Test
  void anExistingBlobIsntReplacedWithoutOverwrite() throws Exception {
    azure.on("PUT " + BLOB + "/uploads/a.txt", r -> new MockResponse.Builder().code(409)
        .addHeader("x-ms-error-code", "BlobAlreadyExists").build());
    ToolCall call = Configs.call(StorageRequest.CHANGE, cfg.name(), "{\"action\":\"upload_blob\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\",\"blob\":\"a.txt\",\"content\":\"x\"}");
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    JsonNode payload = connector.describeAction(call, cfg).payload();
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(payload)))
        .hasMessageContaining("Pass overwrite true");
  }

  @Test
  void anApprovedDeleteRemovesTheBlobAndAskedForSnapshots() throws Exception {
    azure.on("DELETE " + BLOB + "/uploads/old.log", r -> new MockResponse.Builder().code(202).build());
    ToolCall call = Configs.call(StorageRequest.CHANGE, cfg.name(), "{\"action\":\"delete_blob\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\",\"blob\":\"old.log\",\"deleteSnapshots\":true}");
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).contains("DELETE https://acmestage.blob.core.windows.net/uploads/old.log")
        .contains("x-ms-delete-snapshots: include");

    connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(preview.payload()));

    assertThat(azure.to("DELETE " + BLOB + "/uploads/old.log").getFirst().getHeaders().get("x-ms-delete-snapshots"))
        .isEqualTo("include");
  }

  @Test
  void eachStorageToolRefusesTheOthersActions() {
    Classification upload = classify(StorageRequest.READ, "{\"action\":\"upload_blob\",\"account\":\"" + ACCOUNT
        + "\",\"container\":\"uploads\",\"blob\":\"a.txt\",\"content\":\"x\"}", Configs.policy(Env.STAGE));
    assertThat(upload.ruleId()).isEqualTo("stage.cloud.not_a_read");
    assertThat(upload.hint()).contains("azure_storage_change");
    Classification list = classify(StorageRequest.CHANGE, "{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT
        + "\",\"container\":\"uploads\",\"blob\":\"a\"}", Configs.policy(Env.STAGE));
    assertThat(list.ruleId()).isEqualTo("stage.cloud.is_a_read");
  }

  // ── The account and the policy ──────────────────────────────────────────────────────────────────────────────

  @Test
  void anAccountByNameIsLookedUpInItsSubscription() throws Exception {
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Storage/storageAccounts", r -> FakeAzure.json(200,
        "{\"value\":[" + account("other", "westeurope", "https://other.blob.core.windows.net/") + ","
            + account("acmestage", "westeurope", "https://acmestage.blob.core.windows.net/") + "]}"));
    azure.on("GET " + BLOB + "/uploads", r -> FakeAzure.xml(200, "<EnumerationResults><Blobs/></EnumerationResults>"));

    JsonNode s = read("{\"action\":\"list_blobs\",\"account\":\"acmestage\",\"container\":\"uploads\"}")
        .structuredContent();

    assertThat(s.path("account").asString()).isEqualTo(ACCOUNT);
  }

  @Test
  void thePolicyAndTheSubscriptionsBoundTheAccounts() throws Exception {
    Classification other = classify(StorageRequest.READ, "{\"action\":\"list_blobs\",\"account\":\"/subscriptions/"
        + FakeAzure.OTHER_SUB + "/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/x1y2z3\","
        + "\"container\":\"uploads\"}", Configs.policy(Env.STAGE));
    assertThat(other.ruleId()).isEqualTo("stage.cloud.subscription");

    PolicyView masks = Configs.policy(Env.STAGE, "{\"resources\":[\"/subscriptions/*/resourceGroups/stage-data/"
        + "providers/Microsoft.Storage/storageAccounts/acmestage/blobServices/default/containers/public-*\"]}");
    assertThat(classify(StorageRequest.READ, "{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\","
        + "\"container\":\"uploads\"}", masks).ruleId()).isEqualTo("stage.cloud.resource_not_allowed");
    assertThat(classify(StorageRequest.READ, "{\"action\":\"list_blobs\",\"account\":\"acmestage\",\"container\":"
        + "\"public-assets\"}", masks).ruleId()).isEqualTo("stage.cloud.resource_not_allowed");
    assertThat(classify(StorageRequest.READ, "{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\","
        + "\"container\":\"public-assets\"}", masks).kind()).isEqualTo(Classification.Kind.READ);

    PolicyView services = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"compute\"]}");
    assertThat(classify(StorageRequest.READ, "{\"action\":\"list_blobs\",\"account\":\"" + ACCOUNT + "\","
        + "\"container\":\"uploads\"}", services).ruleId()).isEqualTo("stage.cloud.service_not_allowed");

    PolicyView regions = Configs.policy(Env.STAGE, "{\"regions\":[\"eastus\"]}");
    ToolCall call = Configs.call(StorageRequest.READ, cfg.name(), "{\"action\":\"list_blobs\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\"}");
    connector.classify(call, cfg, regions);
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("westeurope").hasMessageContaining("regions");
    assertThat(azure.to("GET " + BLOB)).isEmpty();
  }

  @Test
  void tokensGoOnlyToTheStandardEndpoints() throws Exception {
    azure.on("GET " + ACCOUNT, r -> FakeAzure.json(200, account("acmestage", "westeurope",
        "https://acmestage.z21.blob.storage.azure.net/")));
    ToolCall call = Configs.call(StorageRequest.READ, cfg.name(), "{\"action\":\"list_blobs\",\"account\":\""
        + ACCOUNT + "\",\"container\":\"uploads\"}");
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("acmestage.z21.blob.storage.azure.net").hasMessageContaining("standard endpoints");
    assertThat(azure.to("GET " + BLOB)).isEmpty();
  }
}
