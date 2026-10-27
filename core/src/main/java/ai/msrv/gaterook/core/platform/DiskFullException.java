package ai.msrv.gaterook.core.platform;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The gateway database couldn't write because the disk is full ({@code SQLITE_FULL}). The transaction that met it saved
 * nothing; writes work again as soon as there is space, without a restart. The admin API answers 507 with a title the
 * person can act on.
 */
public class DiskFullException extends DataAccessResourceFailureException {

  private static final long serialVersionUID = 1L;

  public DiskFullException(String message, Throwable cause) {
    super(message, cause);
  }
}
