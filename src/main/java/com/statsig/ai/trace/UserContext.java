package com.statsig.ai.trace;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Lightweight snapshot of the active Statsig user for telemetry purposes. */
public final class UserContext {
  private final String userID;
  private final Map<String, String> customIDs;

  public UserContext(String userID, Map<String, String> customIDs) {
    this.userID = userID != null ? userID : "unknown";
    this.customIDs =
        customIDs != null ? Collections.unmodifiableMap(customIDs) : Collections.emptyMap();
  }

  public String getUserID() {
    return userID;
  }

  public Map<String, String> getCustomIDs() {
    return customIDs;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof UserContext)) {
      return false;
    }
    UserContext other = (UserContext) obj;
    return Objects.equals(userID, other.userID) && Objects.equals(customIDs, other.customIDs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userID, customIDs);
  }
}
