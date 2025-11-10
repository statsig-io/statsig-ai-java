package com.statsig.ai.internal;

public class Utils {
  public static boolean isValidScore(double score) {
    return score >= 0 && score <= 1;
  }
}
