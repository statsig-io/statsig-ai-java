package com.statsig.ai;

import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class TestUtils {
  private TestUtils() {}

  public static String readResource(String resourcePath) throws IOException {
    try (InputStream stream = TestUtils.class.getResourceAsStream(resourcePath)) {
      assertNotNull("Expected resource " + resourcePath + " to exist", stream);
      try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[1024];
        int read;
        while ((read = reader.read(buffer)) != -1) {
          builder.append(buffer, 0, read);
        }
        return builder.toString();
      }
    }
  }
}
