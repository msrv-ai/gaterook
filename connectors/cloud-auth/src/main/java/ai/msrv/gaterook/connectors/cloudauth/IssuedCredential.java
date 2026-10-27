package ai.msrv.gaterook.connectors.cloudauth;

import java.time.Duration;
import java.time.Instant;

/**
 * One row of Secrets › Short-lived (ARCHITECTURE.md §6 {@code GET /api/secrets/short-lived}): who the gateway is
 * right now on behalf of a connection, and when it last got fresh credentials. No credential values.
 *
 * @param owner        the connection (or store) label
 * @param mechanism    {@code GCP impersonation}, {@code AWS AssumeRole}, …
 * @param identity     the impersonated service account or the role ARN
 * @param lastIssuedAt when the current credentials were issued, or {@code null} before the first use
 * @param lifetime     how long issued credentials are valid
 * @param externalId   the ExternalId AssumeRole passes (kit: "AWS AssumeRole with ExternalId"), or {@code null}; an
 *                     identifier the role's trust policy names, not a secret
 */
public record IssuedCredential(String owner, String mechanism, String identity, Instant lastIssuedAt,
    Duration lifetime, String externalId) {

  public IssuedCredential(String owner, String mechanism, String identity, Instant lastIssuedAt, Duration lifetime) {
    this(owner, mechanism, identity, lastIssuedAt, lifetime, null);
  }
}
