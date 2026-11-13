package com.gaterook.connectors.s3;

import com.gaterook.connectors.cloudauth.AwsSessionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The session policy each call on Amazon S3 assumes its role with ({@link S3Settings#generatedPolicy}): the actions of
 * its tool on the connection's buckets, or for an approved change the one action on the one object. AWS enforces
 * them whatever the role itself may do; without a role there is none ({@code null}).
 *
 * <p>Buckets encrypted with KMS need the key through S3: {@code kms:Decrypt} for reads and
 * {@code kms:GenerateDataKey} for writes are allowed only with {@code kms:ViaService} set to the region's S3. STS takes
 * 2,048 characters: a bucket list too long for that keeps the actions and drops the names, and a change on a key too
 * long for it allows its action on the bucket's objects.
 */
final class SessionPolicies {

  static final List<String> LIST_BUCKETS = List.of("s3:ListAllMyBuckets");
  static final List<String> LIST_OBJECTS = List.of("s3:ListBucket");
  static final List<String> READ_OBJECTS = List.of("s3:GetObject", "s3:GetObjectVersion");

  private SessionPolicies() {}

  /** The policy of a read tool, or {@code null} without a generated one. */
  static String read(S3Settings s, String tool) {
    if (!s.generatedPolicy()) {
      return null;
    }
    List<String> names = s.buckets().isEmpty() ? List.of("*") : s.buckets();
    try {
      return readPolicy(s, tool, names);
    } catch (IllegalStateException tooLong) {
      return readPolicy(s, tool, List.of("*"));
    }
  }

  private static String readPolicy(S3Settings s, String tool, List<String> buckets) {
    AwsSessionPolicy p = AwsSessionPolicy.builder();
    switch (tool) {
      case S3Tools.LIST_BUCKETS -> p.allow(LIST_BUCKETS, List.of("*"));
      case S3Tools.LIST_OBJECTS -> p.allow(LIST_OBJECTS, bucketArns(s, buckets));
      default -> {
        // A missing object is a 404 only with s3:ListBucket; without it, S3 answers 403 for it.
        p.allow(READ_OBJECTS, objectArns(s, buckets)).allow(LIST_OBJECTS, bucketArns(s, buckets));
        p.allowWhen(List.of("kms:Decrypt"), List.of("*"), "kms:ViaService", List.of(viaS3(s)));
      }
    }
    return p.toJson();
  }

  /** {@code s3:PutObject} on the one object. */
  static String put(S3Settings s, String bucket, String key) {
    if (!s.generatedPolicy()) {
      return null;
    }
    return change(s, (p, exact) -> p.allow(List.of("s3:PutObject"), List.of(object(s, bucket, key, exact)))
        .allowWhen(List.of("kms:GenerateDataKey", "kms:Decrypt"), List.of("*"), "kms:ViaService", List.of(viaS3(s))));
  }

  /** Reading the source (and its tags, which a copy takes along) and writing the destination. */
  static String copy(S3Settings s, String sourceBucket, String sourceKey, boolean sourceVersion, String bucket,
      String key) {
    if (!s.generatedPolicy()) {
      return null;
    }
    List<String> read = new ArrayList<>(List.of("s3:GetObject", "s3:GetObjectTagging"));
    if (sourceVersion) {
      read.addAll(List.of("s3:GetObjectVersion", "s3:GetObjectVersionTagging"));
    }
    return change(s, (p, exact) -> p.allow(read, List.of(object(s, sourceBucket, sourceKey, exact)))
        .allow(List.of("s3:PutObject", "s3:PutObjectTagging"), List.of(object(s, bucket, key, exact)))
        .allowWhen(List.of("kms:Decrypt", "kms:GenerateDataKey"), List.of("*"), "kms:ViaService",
            List.of(viaS3(s))));
  }

  /** {@code s3:DeleteObject} (with a version id {@code s3:DeleteObjectVersion}) on the one object. */
  static String delete(S3Settings s, String bucket, String key, boolean version) {
    if (!s.generatedPolicy()) {
      return null;
    }
    List<String> actions = version ? List.of("s3:DeleteObject", "s3:DeleteObjectVersion")
        : List.of("s3:DeleteObject");
    return change(s, (p, exact) -> p.allow(actions, List.of(object(s, bucket, key, exact))));
  }

  private interface Statements {
    AwsSessionPolicy add(AwsSessionPolicy policy, boolean exact);
  }

  /** The policy with the exact object ARNs, or, when that is longer than STS takes, with the buckets' objects. */
  private static String change(S3Settings s, Statements statements) {
    try {
      return statements.add(AwsSessionPolicy.builder(), true).toJson();
    } catch (IllegalStateException tooLong) {
      return statements.add(AwsSessionPolicy.builder(), false).toJson();
    }
  }

  private static List<String> bucketArns(S3Settings s, List<String> buckets) {
    List<String> out = new ArrayList<>();
    for (String b : buckets) {
      out.add("arn:" + s.partition() + ":s3:::" + b);
    }
    return out;
  }

  private static List<String> objectArns(S3Settings s, List<String> buckets) {
    List<String> out = new ArrayList<>();
    for (String b : buckets) {
      out.add("arn:" + s.partition() + ":s3:::" + b + "/*");
    }
    return out;
  }

  /** {@code arn:aws:s3:::bucket/key}, the key's {@code * ? $} written as IAM's literals; or {@code bucket/*}. */
  static String object(S3Settings s, String bucket, String key, boolean exact) {
    String prefix = "arn:" + s.partition() + ":s3:::" + bucket + "/";
    if (!exact) {
      return prefix + "*";
    }
    StringBuilder out = new StringBuilder(prefix);
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      switch (c) {
        case '*' -> out.append("${*}");
        case '?' -> out.append("${?}");
        case '$' -> out.append("${$}");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code s3.<region>.amazonaws.com}: the {@code kms:ViaService} of S3 in the region. */
  static String viaS3(S3Settings s) {
    return "s3." + s.region() + ".amazonaws.com" + (s.region().startsWith("cn-") ? ".cn" : "");
  }
}
