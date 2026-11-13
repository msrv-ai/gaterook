package com.gaterook.core.endpoints.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.connections.ConnectionConfigs;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import com.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import com.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import com.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import com.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import com.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;

class WiringTest {
  private final EndpointRows rows = mock(EndpointRows.class);
  private final ToolPairs pairs = mock(ToolPairs.class);
  private final ConnectionConfigs configs = mock(ConnectionConfigs.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final Clock clock = Clock.systemUTC();
  private final Wiring wiring = new Wiring(mock(EndpointConnectionRepository.class),
      mock(EndpointToolRepository.class), rows, pairs, configs, registry, clock);

  @Test
  void incompleteBuiltInKeepsDefaultModesWithoutDiscoveringTools() {
    ConnectorManifest manifest = manifest(ConnectorManifest.Kind.BUILT_IN);
    var written = wiring.wire(endpoint(), incomplete(), manifest,
        t -> t.toolKey().equals("sql_query") ? Mode.READ_ONLY : Mode.APPROVAL);

    assertThat(written).containsExactly(new Wiring.Written("sql_query", "sql_query", Mode.READ_ONLY),
        new Wiring.Written("sql_execute", "sql_execute", Mode.APPROVAL));
    ArgumentCaptor<EndpointToolEntity> saved = ArgumentCaptor.forClass(EndpointToolEntity.class);
    verify(rows, times(2)).save(saved.capture());
    assertThat(saved.getAllValues()).extracting(EndpointToolEntity::getMode)
        .containsExactly(Mode.READ_ONLY, Mode.APPROVAL);
    verify(rows).save(any(EndpointConnectionEntity.class));
    verifyNoInteractions(pairs, configs, registry);
  }

  @Test
  void incompleteExternalConnectorCannotReceiveUnverifiedDefaults() {
    assertThat(wiring.wire(endpoint(), incomplete(), manifest(ConnectorManifest.Kind.PLUGIN),
        t -> Mode.READ_ONLY)).isEmpty();
    verify(rows).save(any(EndpointConnectionEntity.class));
    verify(rows, never()).save(any(EndpointToolEntity.class));
    verifyNoInteractions(pairs, configs, registry);
  }

  private ConnectorManifest manifest(ConnectorManifest.Kind kind) {
    return ConnectorManifest.builder("sql", "1.0.0", kind).tools(List.of(
        ToolDefinition.builder("sql_query").build(), ToolDefinition.builder("sql_execute").build())).build();
  }

  private EndpointEntity endpoint() {
    return new EndpointEntity("dev", Env.DEV, EndpointAccess.STANDARD, Map.of(), clock.instant());
  }

  private static ConnectionConfigs.Stored incomplete() {
    var json = JsonNodeFactory.instance;
    ConnectionConfig config = new ConnectionConfig("incomplete", "database", "sql", Env.DEV, 1,
        json.objectNode(), Set.of(), json.objectNode().put("setupRequired", true));
    return new ConnectionConfigs.Stored(config, true, null, null, null, Map.of(), false);
  }
}
