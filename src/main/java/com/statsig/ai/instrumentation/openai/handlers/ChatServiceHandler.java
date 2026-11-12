package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ChatServiceHandler implements InvocationHandler {
  private final ChatService delegate;
  private final OpenAIWrapperConfig config;

  private ChatServiceHandler(ChatService delegate, OpenAIWrapperConfig config) {
    this.delegate = delegate;
    this.config = config;
  }

  public static ChatService wrap(ChatService service, OpenAIWrapperConfig config) {
    return (ChatService)
        Proxy.newProxyInstance(
            service.getClass().getClassLoader(),
            new Class<?>[] {ChatService.class},
            new ChatServiceHandler(service, config));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if ("completions".equals(method.getName())) {
      ChatCompletionService completions = (ChatCompletionService) method.invoke(delegate, args);
      return ChatCompletionServiceHandler.wrap(completions, config);
    }

    return method.invoke(delegate, args);
  }
}
