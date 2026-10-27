package ai.msrv.gaterook.core.platform.mask;

import java.util.Arrays;

/** A growable {@code long[]}: masked ranges as {@code start << 32 | end}, without boxing. */
final class LongList {

  private long[] values = new long[8];
  private int size;

  void add(long value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, size * 2);
    }
    values[size++] = value;
  }

  void clear() {
    size = 0;
  }

  int size() {
    return size;
  }

  long get(int index) {
    return values[index];
  }

  void set(int index, long value) {
    values[index] = value;
  }

  long[] sorted() {
    long[] out = Arrays.copyOf(values, size);
    Arrays.sort(out);
    return out;
  }
}
