package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.openai.client.OpenAIClient;
import org.junit.Test;
import org.mockito.Mockito;

public class OpenAIWrapperTest {

  @Test
  public void wrapReturnsOriginalClientForNow() {
    OpenAIClient client = Mockito.mock(OpenAIClient.class);
    OpenAIClient wrapped = OpenAIWrapper.wrap(client);
    assertSame(client, wrapped);
  }

  @Test
  public void wrapValidatesInputs() {
    assertThrows(
        NullPointerException.class,
        () -> OpenAIWrapper.wrap(null, OpenAIWrapperConfig.builder().build()));
    OpenAIClient client = Mockito.mock(OpenAIClient.class);
    assertThrows(NullPointerException.class, () -> OpenAIWrapper.wrap(client, null));
  }
}
