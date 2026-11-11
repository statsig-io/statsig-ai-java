package com.statsig.ai.trace;

import java.util.Objects;

/**
 * The Statsig user and activity ID set by {@link StatsigContextUtils#withStatsigContext}. Either
 * may be null.
 */
public final class StatsigContextItem {
  private final UserContext user;
  private final String activityID;

  StatsigContextItem(UserContext user, String activityID) {
    this.user = user;
    this.activityID = activityID;
  }

  /** The user's IDs, or null when no user was given. */
  public UserContext getUser() {
    return user;
  }

  /** The activity ID, or null when none was given. */
  public String getActivityID() {
    return activityID;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatsigContextItem)) {
      return false;
    }
    StatsigContextItem other = (StatsigContextItem) obj;
    return Objects.equals(user, other.user) && Objects.equals(activityID, other.activityID);
  }

  @Override
  public int hashCode() {
    return Objects.hash(user, activityID);
  }
}
