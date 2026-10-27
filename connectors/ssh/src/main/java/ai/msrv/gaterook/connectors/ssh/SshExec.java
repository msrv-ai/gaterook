package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * One command on one exec channel (report 06 §3.6, §7.5): no PTY, stdin closed at once (a command that reads it
 * would hang until the timeout), stdout and stderr apart, each with its own limit. At the deadline and on
 * cancellation the host gets {@code signal TERM} (RFC 4254 §6.9; OpenSSH ≥ 7.9 signals the whole process group),
 * two seconds later {@code KILL}, then the channel closes: closing alone would leave the process running on the
 * host. At an output limit it gets TERM and the channel closes at once (report 06 §3.3).
 */
final class SshExec {

  /** Time the host gets between TERM and KILL. */
  static final Duration KILL_AFTER = Duration.ofSeconds(2);

  /**
   * What happened.
   *
   * @param exitStatus      {@code null} when the process ended by a signal, or the channel closed without one
   * @param truncatedReason {@code output_limit} or {@code timeout}, or {@code null}
   * @param stdoutTotal     bytes the command wrote, including what was cut
   */
  record Result(Integer exitStatus, String exitSignal, byte[] stdout, byte[] stderr, long stdoutTotal,
      long stderrTotal, String truncatedReason, boolean timedOut, boolean cancelled, long durationMs) {

    /** The channel closed with neither an exit status nor a signal and we didn't stop it: the host broke off. */
    boolean brokenOff() {
      return exitStatus == null && exitSignal == null && !timedOut && truncatedReason == null && !cancelled;
    }
  }

  /** The host refused the channel (OpenSSH {@code MaxSessions}): worth one retry on a new session. */
  static final class ChannelRefused extends Exception {
    private static final long serialVersionUID = 1L;

    ChannelRefused(Throwable cause) {
      super(cause.getMessage(), cause, false, false);
    }
  }

  private SshExec() {}

  static Result run(ClientSession session, String wire, Duration budget, long maxOut, long maxErr, ExecContext ctx)
      throws ConnectorException, ChannelRefused {
    long t0 = System.nanoTime();
    AtomicReference<String> reason = new AtomicReference<>();
    ChannelExec ch;
    try {
      ch = session.createExecChannel(wire);
    } catch (IOException e) {
      throw ConnectorException.unavailable("The session to the host closed.", e);
    }
    Capped out = new Capped(maxOut, () -> stop(ch, reason, "output_limit"));
    Capped err = new Capped(maxErr, () -> stop(ch, reason, "output_limit"));
    ch.setOut(out);
    ch.setErr(err);
    AtomicBoolean cancelled = new AtomicBoolean();
    try {
      try {
        ch.open().verify(min(Duration.ofSeconds(10), budget));
      } catch (SshChannelOpenException e) {
        throw new ChannelRefused(e);
      } catch (IOException e) {
        if (SshErrors.root(e) instanceof SshChannelOpenException) {
          throw new ChannelRefused(e);
        }
        throw ConnectorException.unavailable("The host didn't open a channel for the command.", e);
      }
      ctx.onCancel(() -> {
        // TERM at once; the call, still waiting below, sends KILL two seconds later if the command is still there.
        // (MINA's waitFor swallows the interrupt that comes with the cancellation, so the wait goes on.)
        cancelled.set(true);
        signal(ch, "TERM");
      });
      try {
        ch.getInvertedIn().close(); // EOF on stdin
      } catch (IOException ignored) {
        // The command ended already.
      }
      boolean timedOut = !waitClosed(ch, budget, ctx);
      if (timedOut) {
        reason.compareAndSet(null, "timeout");
        if (!cancelled.get()) {
          signal(ch, "TERM");
        }
        if (!waitClosed(ch, KILL_AFTER, null)) {
          signal(ch, "KILL");
          ch.close(true);
        }
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      return new Result(ch.getExitStatus(), ch.getExitSignal(), out.bytes(), err.bytes(), out.total, err.total,
          reason.get(), timedOut, cancelled.get(), ms);
    } finally {
      ch.close(false);
    }
  }

  /** Waits for the channel to close; false at the deadline. Polls the call's cancellation as well. */
  private static boolean waitClosed(ChannelExec ch, Duration wait, ExecContext ctx) {
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      Set<ClientChannelEvent> events = ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),
          Math.max(1, Math.min(left / 1_000_000, 250)));
      if (events.contains(ClientChannelEvent.CLOSED)) {
        return true;
      }
      if (ctx != null && ctx.cancelled()) {
        return false;
      }
    }
  }

  /** At the output limit (report 06 §3.3): TERM and the channel closed, so the writer also meets a closed pipe. */
  private static void stop(ChannelExec ch, AtomicReference<String> reason, String why) {
    reason.compareAndSet(null, why);
    signal(ch, "TERM");
    ch.close(false);
  }

  /** RFC 4254 §6.9; MINA's client has no helper for it. */
  static void signal(ChannelExec ch, String signal) {
    try {
      Buffer b = ch.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, 32);
      b.putUInt(ch.getRecipient());
      b.putString("signal");
      b.putBoolean(false);
      b.putString(signal);
      ch.writePacket(b);
    } catch (IOException | RuntimeException ignored) {
      // The channel is gone: nothing left to stop.
    }
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  /** Keeps the head of a stream up to its limit and counts the rest; the first byte over calls {@code onLimit}. */
  private static final class Capped extends OutputStream {
    private final int max;
    private final Runnable onLimit;
    private byte[] buf = new byte[256];
    private int size;
    long total;
    private boolean over;

    Capped(long max, Runnable onLimit) {
      this.max = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(0, max));
      this.onLimit = onLimit;
    }

    @Override
    public synchronized void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      boolean trip;
      synchronized (this) {
        total += len;
        int room = max - size;
        int keep = Math.max(0, Math.min(room, len));
        if (keep > 0) {
          if (size + keep > buf.length) {
            buf = Arrays.copyOf(buf, Math.min(max, Math.max(buf.length * 2, size + keep)));
          }
          System.arraycopy(b, off, buf, size, keep);
          size += keep;
        }
        trip = keep < len && !over;
        over |= trip;
      }
      if (trip) {
        onLimit.run();
      }
    }

    synchronized byte[] bytes() {
      return Arrays.copyOf(buf, size);
    }
  }
}
