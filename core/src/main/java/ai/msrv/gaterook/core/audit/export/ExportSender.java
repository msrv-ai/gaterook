package ai.msrv.gaterook.core.audit.export;

/** One destination's way of delivering: syslog over TLS, an S3 PUT, a BigQuery insert. */
public interface ExportSender {

  /**
   * Delivers {@code batch}: done when this returns. Sending the same batch again (the same {@link Batch#key()}) lands
   * on the same object or insert ids where the destination can tell them apart.
   *
   * @return the bytes sent
   */
  long send(Batch batch) throws ExportException;

  /** Checks the destination without exporting rows; what it found ("Connected over TLSv1.3 …"). */
  String test() throws ExportException;
}
