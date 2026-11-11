/**
 * S3-compatible object storage ({@code s3}): Amazon S3, Cloudflare R2, DigitalOcean Spaces, Backblaze B2, MinIO and
 * other services that speak S3's API (docs/s3.md). Bucket and object listings, metadata and bounded text reads; one
 * approved put, copy or delete per change. Requests are signed with SigV4 and sent through the core's guarded egress;
 * on Amazon S3 with an IAM role every call carries a session policy that AWS enforces.
 */
package ai.msrv.gaterook.connectors.s3;
