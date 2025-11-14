package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.junit.Test;

public class OpenAIWrapperTest {

  @Test
  public void wrapValidatesInputs() {
    assertThrows(NullPointerException.class, () -> OpenAIWrapper.wrap(null));
    OpenAIClient client = mock(OpenAIClient.class);
    assertNotNull(OpenAIWrapper.wrap(client, null));
  }

  @Test
  public void wrapInstrumentsChatCompletions() {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(chatCompletionService);

    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    when(chatCompletionService.create(params)).thenReturn(null);

    OpenAIClient wrapped = OpenAIWrapper.wrap(client);
    wrapped.chat().completions().create(params);

    verify(chatCompletionService).create(params);
  }

  @Test
  public void wrapReturnsProxy() {
    OpenAIClient client = mock(OpenAIClient.class);
    OpenAIClient wrapped = OpenAIWrapper.wrap(client);
    assertNotNull(wrapped);
  }
}
