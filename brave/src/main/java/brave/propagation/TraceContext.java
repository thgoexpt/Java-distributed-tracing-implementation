package brave.propagation;

import brave.internal.Nullable;
import brave.internal.TraceContexts;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static brave.internal.HexCodec.lenientLowerHexToUnsignedLong;
import static brave.internal.HexCodec.writeHexLong;
import static brave.internal.Lists.ensureImmutable;
import static brave.internal.TraceContexts.FLAG_SAMPLED;
import static brave.internal.TraceContexts.FLAG_SAMPLED_SET;
import static brave.internal.TraceContexts.FLAG_SHARED;

/**
 * Contains trace identifiers and sampling data propagated in and out-of-process.
 *
 * <p>Particularly, this includes trace identifiers and sampled state.
 *
 * <p>The implementation was originally {@code com.github.kristofa.brave.SpanId}, which was a
 * port of {@code com.twitter.finagle.tracing.TraceId}. Unlike these mentioned, this type does not
 * expose a single binary representation. That's because propagation forms can now vary.
 */
//@Immutable
public final class TraceContext extends SamplingFlags {
  static final Logger LOG = Logger.getLogger(TraceContext.class.getName());

  /**
   * Used to send the trace context downstream. For example, as http headers.
   *
   * <p>For example, to put the context on an {@link java.net.HttpURLConnection}, you can do this:
   * <pre>{@code
   * // in your constructor
   * injector = tracing.propagation().injector(URLConnection::setRequestProperty);
   *
   * // later in your code, reuse the function you created above to add trace headers
   * HttpURLConnection connection = (HttpURLConnection) new URL("http://myserver").openConnection();
   * injector.inject(span.context(), connection);
   * }</pre>
   */
  public interface Injector<C> {
    /**
     * Usually calls a setter for each propagation field to send downstream.
     *
     * @param traceContext possibly unsampled.
     * @param carrier holds propagation fields. For example, an outgoing message or http request.
     */
    void inject(TraceContext traceContext, C carrier);
  }

  /**
   * Used to continue an incoming trace. For example, by reading http headers.
   *
   * @see brave.Tracer#nextSpan(TraceContextOrSamplingFlags)
   */
  public interface Extractor<C> {

    /**
     * Returns either a trace context or sampling flags parsed from the carrier. If nothing was
     * parsable, sampling flags will be set to {@link SamplingFlags#EMPTY}.
     *
     * @param carrier holds propagation fields. For example, an incoming message or http request.
     */
    TraceContextOrSamplingFlags extract(C carrier);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** When non-zero, the trace containing this span uses 128-bit trace identifiers. */
  public long traceIdHigh() {
    return traceIdHigh;
  }

  /** Unique 8-byte identifier for a trace, set on all spans within it. */
  public long traceId() {
    return traceId;
  }

  /**
   * The parent's {@link #spanId} or null if this the root span in a trace.
   *
   * @see #parentIdAsLong()
   */
  @Nullable public final Long parentId() {
    return parentId != 0 ? parentId : null;
  }

  /**
   * Like {@link #parentId()} except returns a primitive where zero implies absent.
   *
   * <p>Using this method will avoid allocation, so is encouraged when copying data.
   */
  public long parentIdAsLong() {
    return parentId;
  }

  /**
   * Unique 8-byte identifier of this span within a trace.
   *
   * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@linkplain #spanId}).
   */
  public long spanId() {
    return spanId;
  }

  /**
   * True if we are contributing to a span started by another tracer (ex on a different host).
   * Defaults to false.
   *
   * <h3>Impact on indexing</h3>
   * <p>When an RPC trace is client-originated, it will be sampled and the same span ID is used for
   * the server side. The shared flag helps prioritize timestamp and duration indexing in favor of
   * the client. In v1 format, there is no shared flag, so it implies converters should not store
   * timestamp and duration on the server span explicitly.
   */
  public boolean shared() {
    return (flags & FLAG_SHARED) == FLAG_SHARED;
  }

  /**
   * Returns a list of additional data propagated through this trace.
   *
   * <p>The contents are intentionally opaque, deferring to {@linkplain Propagation} to define. An
   * example implementation could be storing a class containing a correlation value, which is
   * extracted from incoming requests and injected as-is onto outgoing requests.
   *
   * <p>Implementations are responsible for scoping any data stored here. This can be performed
   * when {@link Propagation.Factory#decorate(TraceContext)} is called.
   */
  public List<Object> extra() {
    return extra;
  }

  /**
   * Returns an {@linkplain #extra() extra} of the given type if present or null if not.
   *
   * <p>Note: it is the responsibility of {@link Propagation.Factory#decorate(TraceContext)}
   * to consolidate extra fields. If it doesn't, there could be multiple instance of a given type
   * and this can break logic.
   */
  public @Nullable <T> T findExtra(Class<T> type) {
    return findExtra(type, extra);
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  /** Returns the hex representation of the span's trace ID */
  public String traceIdString() {
    if (traceIdHigh != 0) {
      char[] result = new char[32];
      writeHexLong(result, 0, traceIdHigh);
      writeHexLong(result, 16, traceId);
      return new String(result);
    }
    char[] result = new char[16];
    writeHexLong(result, 0, traceId);
    return new String(result);
  }

  /** Returns {@code $traceId/$spanId} */
  @Override
  public String toString() {
    boolean traceHi = traceIdHigh != 0;
    char[] result = new char[((traceHi ? 3 : 2) * 16) + 1]; // 2 ids and the delimiter
    int pos = 0;
    if (traceHi) {
      writeHexLong(result, pos, traceIdHigh);
      pos += 16;
    }
    writeHexLong(result, pos, traceId);
    pos += 16;
    result[pos++] = '/';
    writeHexLong(result, pos, spanId);
    return new String(result);
  }

  public static final class Builder {
    long traceIdHigh, traceId, parentId, spanId;
    int flags;
    List<Object> extra = Collections.emptyList();

    Builder(TraceContext context) { // no external implementations
      traceIdHigh = context.traceIdHigh;
      traceId = context.traceId;
      parentId = context.parentId;
      spanId = context.spanId;
      flags = context.flags;
      extra = context.extra;
    }

    /** @see TraceContext#traceIdHigh() */
    public Builder traceIdHigh(long traceIdHigh) {
      this.traceIdHigh = traceIdHigh;
      return this;
    }

    /** @see TraceContext#traceId() */
    public Builder traceId(long traceId) {
      this.traceId = traceId;
      return this;
    }

    /** @see TraceContext#parentIdAsLong() */
    public Builder parentId(long parentId) {
      this.parentId = parentId;
      return this;
    }

    /** @see TraceContext#parentId() */
    public Builder parentId(@Nullable Long parentId) {
      if (parentId == null) parentId = 0L;
      this.parentId = parentId;
      return this;
    }

    /** @see TraceContext#spanId() */
    public Builder spanId(long spanId) {
      this.spanId = spanId;
      return this;
    }

    /** @see TraceContext#sampled() */
    public Builder sampled(boolean sampled) {
      flags = TraceContexts.sampled(sampled, flags);
      return this;
    }

    /** @see TraceContext#sampled() */
    public Builder sampled(@Nullable Boolean sampled) {
      if (sampled == null) {
        flags &= ~(FLAG_SAMPLED_SET | FLAG_SAMPLED);
        return this;
      }
      return sampled(sampled.booleanValue());
    }

    /** @see TraceContext#debug() */
    public Builder debug(boolean debug) {
      flags = SamplingFlags.debug(debug, flags);
      return this;
    }

    /** @see TraceContext#shared() */
    public Builder shared(boolean shared) {
      if (shared) {
        flags |= FLAG_SHARED;
      } else {
        flags &= ~FLAG_SHARED;
      }
      return this;
    }

    /**
     * Shares the input with the builder, replacing any current data in the builder.
     *
     * @see TraceContext#extra()
     */
    public final Builder extra(List<Object> extra) {
      this.extra = extra;
      return this;
    }

    /**
     * Returns true when {@link TraceContext#traceId()} and potentially also {@link
     * TraceContext#traceIdHigh()} were parsed from the input. This assumes the input is valid, an
     * up to 32 character lower-hex string.
     *
     * <p>Returns boolean, not this, for conditional, exception free parsing:
     *
     * <p>Example use:
     * <pre>{@code
     * // Attempt to parse the trace ID or break out if unsuccessful for any reason
     * String traceIdString = getter.get(carrier, key);
     * if (!builder.parseTraceId(traceIdString, propagation.traceIdKey)) {
     *   return TraceContextOrSamplingFlags.EMPTY;
     * }
     * }</pre>
     *
     * @param traceIdString the 1-32 character lowerhex string
     * @param key the name of the propagation field representing the trace ID; only using in
     * logging
     * @return false if the input is null or malformed
     */
    // temporarily package protected until we figure out if this is reusable enough to expose
    final boolean parseTraceId(String traceIdString, Object key) {
      if (isNull(key, traceIdString)) return false;
      int length = traceIdString.length();
      if (invalidIdLength(key, length, 32)) return false;

      // left-most characters, if any, are the high bits
      int traceIdIndex = Math.max(0, length - 16);
      if (traceIdIndex > 0) {
        traceIdHigh = lenientLowerHexToUnsignedLong(traceIdString, 0, traceIdIndex);
        if (traceIdHigh == 0) {
          maybeLogNotLowerHex(key, traceIdString);
          return false;
        }
      }

      // right-most up to 16 characters are the low bits
      traceId = lenientLowerHexToUnsignedLong(traceIdString, traceIdIndex, length);
      if (traceId == 0) {
        maybeLogNotLowerHex(key, traceIdString);
        return false;
      }
      return true;
    }

    /** Parses the parent id from the input string. Returns true if the ID was missing or valid. */
    final <C, K> boolean parseParentId(Propagation.Getter<C, K> getter, C carrier, K key) {
      String parentIdString = getter.get(carrier, key);
      if (parentIdString == null) return true; // absent parent is ok
      int length = parentIdString.length();
      if (invalidIdLength(key, length, 16)) return false;

      parentId = lenientLowerHexToUnsignedLong(parentIdString, 0, length);
      if (parentId != 0) return true;
      maybeLogNotLowerHex(key, parentIdString);
      return false;
    }

    /** Parses the span id from the input string. Returns true if the ID is valid. */
    final <C, K> boolean parseSpanId(Propagation.Getter<C, K> getter, C carrier, K key) {
      String spanIdString = getter.get(carrier, key);
      if (isNull(key, spanIdString)) return false;
      int length = spanIdString.length();
      if (invalidIdLength(key, length, 16)) return false;

      spanId = lenientLowerHexToUnsignedLong(spanIdString, 0, length);
      if (spanId == 0) {
        maybeLogNotLowerHex(key, spanIdString);
        return false;
      }
      return true;
    }

    boolean invalidIdLength(Object key, int length, int max) {
      if (length > 1 && length <= max) return false;
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine(key + " should be a 1 to " + max + " character lower-hex string with no prefix");
      }
      return true;
    }

    boolean isNull(Object key, String maybeNull) {
      if (maybeNull != null) return false;
      if (LOG.isLoggable(Level.FINE)) LOG.fine(key + " was null");
      return true;
    }

    void maybeLogNotLowerHex(Object key, String notLowerHex) {
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine(key + ": " + notLowerHex + " is not a lower-hex string");
      }
    }

    public final TraceContext build() {
      String missing = "";
      if (traceId == 0L) missing += " traceId";
      if (spanId == 0L) missing += " spanId";
      if (!"".equals(missing)) throw new IllegalStateException("Missing: " + missing);
      return new TraceContext(
          flags, traceIdHigh, traceId, parentId, spanId, ensureImmutable(extra)
      );
    }

    Builder() { // no external implementations
    }
  }

  final long traceIdHigh, traceId, parentId, spanId;
  final List<Object> extra;

  TraceContext(
      int flags,
      long traceIdHigh,
      long traceId,
      long parentId,
      long spanId,
      List<Object> extra
  ) {
    super(flags);
    this.traceIdHigh = traceIdHigh;
    this.traceId = traceId;
    this.parentId = parentId;
    this.spanId = spanId;
    this.extra = extra;
  }

  /**
   * Includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} and the
   * {@link #shared() shared flag}.
   *
   * <p>The shared flag is included to have parity with the {@link #hashCode()}.
   */
  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof TraceContext)) return false;
    TraceContext that = (TraceContext) o;
    return (traceIdHigh == that.traceIdHigh)
        && (traceId == that.traceId)
        && (spanId == that.spanId)
        && ((flags & FLAG_SHARED) == (that.flags & FLAG_SHARED));
  }

  /**
   * Includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} and the
   * {@link #shared() shared flag}.
   *
   * <p>The shared flag is included in the hash code to ensure loopback span data are partitioned
   * properly. For example, if a client calls itself, the server-side shouldn't overwrite the client
   * side.
   */
  @Override public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= (int) ((traceIdHigh >>> 32) ^ traceIdHigh);
    h *= 1000003;
    h ^= (int) ((traceId >>> 32) ^ traceId);
    h *= 1000003;
    h ^= (int) ((spanId >>> 32) ^ spanId);
    h *= 1000003;
    h ^= flags & FLAG_SHARED;
    return h;
  }

  static <T> T findExtra(Class<T> type, List<Object> extra) {
    if (type == null) throw new NullPointerException("type == null");
    for (int i = 0, length = extra.size(); i < length; i++) {
      Object nextExtra = extra.get(i);
      if (nextExtra.getClass() == type) return (T) nextExtra;
    }
    return null;
  }
}
