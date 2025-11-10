package com.statsig.ai;

public class AIEvalGradeData {
  public String sessionID;

  public AIEvalGradeData(String sessionID) {
    this.sessionID = sessionID;
  }

  public String getSessionID() {
    return sessionID;
  }

  public void setSessionID(String sessionID) {
    this.sessionID = sessionID;
  }
}
