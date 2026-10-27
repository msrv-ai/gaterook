package ai.msrv.gaterook.core.mcpservers.internal.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code mcp_server}. */
public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {

  Optional<McpServerEntity> findByConnectionId(String connectionId);

  Optional<McpServerEntity> findByToolPrefix(String toolPrefix);

  boolean existsByToolPrefix(String toolPrefix);
}
