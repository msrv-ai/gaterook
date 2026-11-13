package com.gaterook.core.admin.secrets.stores;

import com.gaterook.core.admin.ApiRequestException;
import com.gaterook.core.admin.Reasons;
import com.gaterook.core.platform.FileGrants;
import com.gaterook.core.endpoints.ConnectionImportService;
import com.gaterook.core.secrets.SecretInfo;
import com.gaterook.core.secrets.SecretStores;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Secrets › External stores (ARCHITECTURE.md §6, report 12 §4.3, §5): connect, edit, test and disconnect HashiCorp
 * Vault / OpenBao, GCP Secret Manager, AWS Secrets Manager and Azure Key Vault; test a reference before it is saved;
 * point a stored reference elsewhere. Write-only like the rest of Secrets: the sign-in values of a store arrive in {@code secrets}
 * and are encrypted here; no answer has a field that could hold one, and a test never reports the length, hash or
 * prefix of a value.
 */
@RestController
@RegisterReflectionForBinding({SecretStoresController.ImportCreate.class, SecretStoresController.Create.class, SecretStoresController.Update.class,
    SecretStoresController.Delete.class, SecretStoresController.Reason.class, SecretStoresController.RefTestRequest.class,
    SecretStoresController.ReplaceReference.class, SecretStoresController.Reference.class,
    SecretStoresController.VaultBrowseRequest.class, SecretStores.VaultBrowse.class, SecretStores.VaultEntry.class,
    SecretStores.VaultReferences.class, SecretStores.VaultDiscoveryIssue.class,
    SecretStores.StoreInfo.class, SecretStores.Reference.class, SecretStores.SignInSecret.class,
    SecretStores.TestResult.class, SecretStores.Permission.class, SecretStores.Hint.class, SecretStores.Check.class,
    SecretStores.RefTest.class, SecretStores.Disconnected.class, SecretStores.Issued.class, SecretStores.Source.class})
class SecretStoresController {

  /**
   * {@code POST /api/secret-stores}.
   *
   * @param kind    {@code vault}, {@code gcp-sm}, {@code aws-sm}, {@code azure-kv}
   * @param config  the settings of the kind's form ({@code GET /api/secret-stores/form?kind=})
   * @param secrets its sign-in values by JSON pointer ({@code /auth/secretId}), encrypted here and zeroed
   * @param files   files the person chose in GateRook's file dialog, by settings pointer: the one-time token of each
   *                ({@code /auth/file} of a Vault token file); the API takes no raw path
   */
  record Create(String kind, String name, JsonNode config, Map<String, char[]> secrets, Map<String, String> files,
      String reason) {
    @Override
    public String toString() {
      return "Create[" + kind + " " + name + ", " + (secrets == null ? 0 : secrets.size()) + " secret(s)]";
    }
  }

  /** Prepared credentials are used only when the person selects their field before Connect. */
  record ImportCreate(String name, JsonNode config, Map<String, char[]> secrets, Map<String, String> files,
      List<String> preparedFields, String existingStore, String reason) {
    @Override public String toString() { return "ImportCreate[credentials redacted]"; }
  }

  /**
   * {@code PUT /api/secret-stores/{id}}: sign-in fields left out keep their stored values, a token file not chosen
   * again stays the stored one.
   */
  record Update(String name, JsonNode config, Map<String, char[]> secrets, Map<String, String> files, Long version,
      String reason) {
    @Override
    public String toString() {
      return "Update[" + name + ", " + (secrets == null ? 0 : secrets.size()) + " secret(s)]";
    }
  }

  /** Disconnect names the store again ({@code confirmName}), like every danger confirmation. */
  record Delete(String confirmName, String reason) {}

  record Reason(String reason) {}

  /** Test reference: {@code store} when more than one store of the kind is connected. */
  record RefTestRequest(String uri, String store) {}

  record VaultBrowseRequest(String path) {}

  record ReplaceReference(String uri, String store, String reason) {}

  /** The stored reference after Replace reference: where it points, never what it holds. */
  record Reference(String id, String name, String owner, String field, String source, String ref, String store,
      String remoteVersion, String state) {}

  private final SecretStores stores;
  private final FileGrants grants;
  private final ConnectionImportService imports;

  SecretStoresController(SecretStores stores, FileGrants grants, ConnectionImportService imports) {
    this.stores = stores;
    this.grants = grants;
    this.imports = imports;
  }

  @GetMapping("/api/secret-stores")
  List<SecretStores.StoreInfo> list() {
    return stores.list();
  }

  /** The form of one kind: its JSON Schema and which of its fields are sign-in secrets. */
  @GetMapping("/api/secret-stores/form")
  JsonNode form(@RequestParam(name = "kind", required = false) String kind) {
    if (kind == null || kind.isBlank()) {
      throw ApiRequestException.unprocessable("Kind missing", "Choose vault, gcp-sm, aws-sm or azure-kv.", "/kind");
    }
    return stores.schema(kind);
  }

  /** Where a connection's secret field can come from, and why a source can't be chosen yet. */
  @GetMapping("/api/secret-stores/sources")
  List<SecretStores.Source> sources() {
    return stores.sources();
  }

  @GetMapping("/api/secret-stores/{id}")
  SecretStores.StoreInfo get(@PathVariable String id) {
    return stores.get(id);
  }

  @PostMapping("/api/secret-stores")
  ResponseEntity<SecretStores.StoreInfo> create(@RequestBody Create request) {
    Map<String, char[]> secrets = copy(request.secrets());
    try {
      Map<String, Path> files = chosen(request.files());
      SecretStores.StoreInfo created = stores.create(request.kind(), request.name(), request.config(), secrets,
          files, Reasons.check(request.reason()));
      redeem(request.files());
      return ResponseEntity.created(URI.create("/api/secret-stores/" + created.id())).body(created);
    } finally {
      zero(secrets);
      zero(request.secrets());
    }
  }

  /** AI tools only stage these proposals; connecting one requires a signed local admin request. */
  @PostMapping("/api/connection-imports/{sessionId}/stores/{proposalId}/connect")
  SecretStores.StoreInfo connectImport(@PathVariable String sessionId, @PathVariable String proposalId,
      @RequestBody ImportCreate request) {
    try {
      String storeId;
      if (request.existingStore() != null) {
        if (request.name() != null || request.config() != null || request.secrets() != null
            || request.files() != null || request.preparedFields() != null) {
          throw ApiRequestException.unprocessable("Choose one store action", "Choose an existing store or configure a new one.", "/existingStore");
        }
        storeId = imports.connectStore(sessionId, proposalId, null, null, null, (proposal, values) -> {
          SecretStores.StoreInfo existing = stores.get(request.existingStore());
          if (!"vault".equals(existing.kind()) || !existing.verified()) {
            throw ApiRequestException.unprocessable("Choose a verified Vault store", "Review the selected store before using it.", "/existingStore");
          }
          VaultIdentity expected = vaultIdentity(proposal.path("config"));
          if (expected == null || !expected.equals(vaultIdentity(existing.config()))) {
            throw ApiRequestException.unprocessable("Vault address changed", "The existing store no longer matches the discovered address and namespace. Refresh and review it again.", "/existingStore");
          }
          return existing.id();
        });
      } else {
        String method = request.config() == null ? "approle" : request.config().path("auth").path("method").asString("approle");
        storeId = imports.connectStore(sessionId, proposalId, method, request.preparedFields(), request.secrets(), (proposal, values) -> {
          Map<String, Path> files = chosen(request.files());
          SecretStores.StoreInfo created = stores.create("vault", request.name(), request.config(), values, files,
              Reasons.check(request.reason()));
          redeem(request.files());
          return created.id();
        });
      }
      return stores.get(storeId);
    } finally { zero(request.secrets()); }
  }

  private record VaultIdentity(String scheme, String host, int port, String path, String namespace) {}

  /** Same authority, base path and case-sensitive namespace as the import UI, without DNS or secret reads. */
  private static VaultIdentity vaultIdentity(JsonNode config) {
    try {
      URI uri = URI.create(config.path("address").asString("").strip()).normalize();
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
      int port = uri.getPort() < 0 ? scheme.equals("https") ? 443 : 80 : uri.getPort();
      String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
      String namespace = config.path("namespace").asString("").strip().replaceAll("^/+|/+$", "");
      return new VaultIdentity(scheme, uri.getHost().toLowerCase(Locale.ROOT), port, path, namespace);
    } catch (IllegalArgumentException e) { return null; }
  }

  @PutMapping("/api/secret-stores/{id}")
  SecretStores.StoreInfo update(@PathVariable String id, @RequestBody Update request) {
    Map<String, char[]> secrets = copy(request.secrets());
    try {
      if (request.version() == null) {
        throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
      }
      Map<String, Path> files = chosen(request.files());
      SecretStores.StoreInfo updated = stores.update(id, request.name(), request.config(), secrets, files,
          request.version(), Reasons.check(request.reason()));
      redeem(request.files());
      return updated;
    } finally {
      zero(secrets);
      zero(request.secrets());
    }
  }

  /**
   * The paths of the file tokens, still valid afterwards: a store refused for another reason (its address) can be
   * sent again without choosing the file again.
   */
  private Map<String, Path> chosen(Map<String, String> files) {
    Map<String, Path> out = new LinkedHashMap<>();
    if (files != null) {
      files.forEach((pointer, token) -> {
        try {
          out.put(pointer, grants.peek(token, FileGrants.Purpose.VAULT_TOKEN));
        } catch (IllegalArgumentException e) {
          throw ApiRequestException.unprocessable("Choose the file again", e.getMessage(), "/files/"
              + pointer.replace("~", "~0").replace("/", "~1"));
        }
      });
    }
    return out;
  }

  /** Each file token is used once: by the store it was chosen for. */
  private void redeem(Map<String, String> files) {
    if (files != null) {
      files.values().forEach(token -> {
        try {
          grants.take(token, FileGrants.Purpose.VAULT_TOKEN);
        } catch (IllegalArgumentException alreadyUsed) {
          // Another request with the same choice won: the store has the path either way.
        }
      });
    }
  }

  @DeleteMapping("/api/secret-stores/{id}")
  SecretStores.Disconnected delete(@PathVariable String id, @RequestBody(required = false) Delete request) {
    return stores.delete(id, request == null ? null : request.confirmName(),
        request == null ? null : Reasons.check(request.reason()));
  }

  @PostMapping("/api/secret-stores/{id}/test")
  SecretStores.TestResult test(@PathVariable String id) {
    return stores.test(id);
  }

  /** Lists names in one Vault folder; selecting a secret uses Test reference to discover its keys. */
  @PostMapping("/api/secret-stores/{id}/vault/browse")
  SecretStores.VaultBrowse browseVault(@PathVariable String id, @RequestBody VaultBrowseRequest request) {
    return stores.browseVault(id, request.path());
  }

  /** Loads selectable KV paths and key names for the connection form's autocomplete. */
  @PostMapping("/api/secret-stores/{id}/vault/references")
  SecretStores.VaultReferences vaultReferences(@PathVariable String id) {
    return stores.vaultReferences(id);
  }

  @PostMapping("/api/secret-stores/{id}/confirm-integrity")
  SecretStores.StoreInfo confirmIntegrity(@PathVariable String id, @RequestBody(required = false) Reason request) {
    return stores.confirmIntegrity(id, request == null ? null : Reasons.check(request.reason()));
  }

  /** Test reference: resolved now, nothing kept ("Found · latest → v4 · created Oct 16"). */
  @PostMapping("/api/secret-refs/test")
  SecretStores.RefTest testReference(@RequestBody RefTestRequest request) {
    if (request.uri() == null || request.uri().isBlank()) {
      throw ApiRequestException.unprocessable("Reference missing", "Enter a reference such as "
          + "vault://kv/stage/db#password.", "/uri");
    }
    return stores.testReference(request.uri(), request.store());
  }

  /** Points a stored reference elsewhere; its cached value and lease go, the next call reads the new one. */
  @PutMapping("/api/secrets/{id}/reference")
  Reference replaceReference(@PathVariable String id, @RequestBody ReplaceReference request) {
    if (request.uri() == null || request.uri().isBlank()) {
      throw ApiRequestException.unprocessable("Reference missing", "Enter the new reference.", "/uri");
    }
    SecretInfo s = stores.replaceReference(id, request.uri(), request.store(), Reasons.check(request.reason()));
    return new Reference(s.id(), s.name(), s.ownerLabel(), s.field(), s.source(), s.ref(), s.store(),
        s.remoteVersion(), s.state());
  }

  private static Map<String, char[]> copy(Map<String, char[]> given) {
    Map<String, char[]> out = new LinkedHashMap<>();
    if (given != null) {
      given.forEach((field, value) -> out.put(field, value == null ? new char[0] : value.clone()));
    }
    return out;
  }

  private static void zero(Map<String, char[]> values) {
    if (values != null) {
      values.values().forEach(v -> {
        if (v != null) {
          Arrays.fill(v, '\0');
        }
      });
    }
  }
}
