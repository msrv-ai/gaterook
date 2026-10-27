package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.core.audit.internal.AuditHead;
import org.springframework.stereotype.Component;

/**
 * The anchor of the audit hash chains as other modules may touch it (ARCHITECTURE.md §4 "Audit hash chain"): only
 * to anchor a brand-new, empty history before something fills it ahead of the start check.
 */
@Component
public class AuditChain {

  private final AuditHead head;

  AuditChain(AuditHead head) {
    this.head = head;
  }

  /**
   * Anchors the history now if it has no rows and no anchor yet (the demo seed calls it before it writes anything).
   *
   * @return whether the history has an anchor now; {@code false} while the master key is locked, or when rows exist
   *     without an anchor: the start check reports those as a break
   */
  public boolean anchorIfEmpty() {
    return head.anchorIfEmpty();
  }
}
