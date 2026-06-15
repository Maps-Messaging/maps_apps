package io.mapsmessaging.audit.viewer;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;
import lombok.Getter;

public enum TestAuditMessages implements LogMessage {

  TEST_AUDIT_EVENT(
      LEVEL.AUDIT,
      CATEGORY.AUDIT,
      "Test audit message {} {}"
  );

  private final @Getter LEVEL level;
  private final @Getter Category category;
  private final @Getter String message;
  private final @Getter int parameterCount;

  TestAuditMessages(LEVEL level, Category category, String message) {
    this.level = level;
    this.category = category;
    this.message = message;
    this.parameterCount = countParameters(message);
  }

  private int countParameters(String message) {
    int count = 0;
    int location = message.indexOf("{}");

    while (location != -1) {
      count++;
      location = message.indexOf("{}", location + 2);
    }

    return count;
  }

  private enum CATEGORY implements Category {

    AUDIT("Test Audit");

    private final @Getter String description;

    CATEGORY(String description) {
      this.description = description;
    }

    @Override
    public String getDivision() {
      return "Audit";
    }
  }
}