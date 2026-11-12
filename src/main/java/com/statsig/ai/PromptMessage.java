package com.statsig.ai;

import java.util.Objects;

/** A rendered prompt message, from {@link PromptVersion#getPromptMessages}. */
public final class PromptMessage {
  private final String role;
  private final String content;

  public PromptMessage(String role, String content) {
    this.role = role;
    this.content = content;
  }

  /** The role, such as {@code system} or {@code user}. */
  public String getRole() {
    return role;
  }

  /** The content, with the template variables filled in. */
  public String getContent() {
    return content;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PromptMessage)) {
      return false;
    }
    PromptMessage other = (PromptMessage) o;
    return Objects.equals(role, other.role) && Objects.equals(content, other.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(role, content);
  }

  @Override
  public String toString() {
    return "PromptMessage{role=" + role + ", content=" + content + "}";
  }
}
