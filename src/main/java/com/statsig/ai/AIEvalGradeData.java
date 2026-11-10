package com.statsig.ai;

import java.util.Map;

public class AIEvalGradeData {
  public String sessionID;
  private String activityID;
  private String traceID;
  private String spanID;
  private String reasoning;
  private Map<String, String> metadata;

  public AIEvalGradeData() {}

  public AIEvalGradeData(String sessionID) {
    this.sessionID = sessionID;
  }

  public String getSessionID() {
    return sessionID;
  }

  public void setSessionID(String sessionID) {
    this.sessionID = sessionID;
  }

  public String getActivityID() {
    return activityID;
  }

  /** Added to the user's custom IDs under {@code statsig.activity_id} for this event. */
  public void setActivityID(String activityID) {
    this.activityID = activityID;
  }

  public String getTraceID() {
    return traceID;
  }

  /**
   * Trace to attach this grade to. Defaults to the active span's trace. Set this (and {@link
   * #setSpanID(String)}) when grading after the call's span has ended.
   */
  public void setTraceID(String traceID) {
    this.traceID = traceID;
  }

  public String getSpanID() {
    return spanID;
  }

  /**
   * Span to attach this grade to. Defaults to the active span. Set this (and {@link
   * #setTraceID(String)}) when grading after the call's span has ended.
   */
  public void setSpanID(String spanID) {
    this.spanID = spanID;
  }

  public String getReasoning() {
    return reasoning;
  }

  public void setReasoning(String reasoning) {
    this.reasoning = reasoning;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  /** Extra metadata merged into the event; these entries win over the SDK's own keys. */
  public void setMetadata(Map<String, String> metadata) {
    this.metadata = metadata;
  }
}
