/**
 * Kubernetes connector ({@code kubernetes}), report 08.
 *
 * <p>Reads ({@code k8s_get}, {@code k8s_logs}) and two mutations that always wait for approval:
 * {@code k8s_rollout_restart} (merge patch with a uid precondition) and {@code k8s_apply} (server-side dry run, the
 * diff the approver sees, a resourceVersion precondition). No delete, scale, exec or port-forward tools exist.
 * Step C6 implements execution; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.kubernetes;
