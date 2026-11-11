package com.statsig.ai.evals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A score plus metadata about it, such as a judge's reasoning. Metadata values that aren't strings
 * are sent to Statsig as JSON.
 */
public final class ScoreWithMetadata {
  private final double score;
  private final Map<String, Object> metadata;

  private ScoreWithMetadata(double score, Map<String, ?> metadata) {
    this.score = score;
    this.metadata =
        metadata == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
  }

  /** {@code metadata} may be null. */
  public static ScoreWithMetadata of(double score, Map<String, ?> metadata) {
    return new ScoreWithMetadata(score, metadata);
  }

  /** A pass (1) or a fail (0). {@code metadata} may be null. */
  public static ScoreWithMetadata of(boolean passed, Map<String, ?> metadata) {
    return new ScoreWithMetadata(passed ? 1.0 : 0.0, metadata);
  }

  public double getScore() {
    return score;
  }

  /** The metadata, or null when there is none. */
  public Map<String, Object> getMetadata() {
    return metadata;
  }
}
