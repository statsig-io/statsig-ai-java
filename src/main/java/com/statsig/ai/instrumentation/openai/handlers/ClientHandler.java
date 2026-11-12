package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.client.OpenAIClient;
import com.openai.services.blocking.ChatService;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public final class ClientHandler implements InvocationHandler {
  private final OpenAIClient delegate;
  private final OpenAIWrapperConfig config;

  public ClientHandler(OpenAIClient delegate, OpenAIWrapperConfig config) {
    this.delegate = delegate;
    this.config = config;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if (method.getDeclaringClass() == Object.class) {
      return method.invoke(delegate, args);
    }

    if ("chat".equals(method.getName())) {
      ChatService service = (ChatService) method.invoke(delegate, args);
      return ChatServiceHandler.wrap(service, config);
    }

    return method.invoke(delegate, args);
  }
}
