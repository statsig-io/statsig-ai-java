package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.client.OpenAIClient;
import com.openai.services.blocking.ChatService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public final class ClientHandler implements InvocationHandler {
  private final OpenAIClient delegate;

  public ClientHandler(OpenAIClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if (method.getDeclaringClass() == Object.class) {
      return method.invoke(delegate, args);
    }

    if ("chat".equals(method.getName())) {
      ChatService service = (ChatService) method.invoke(delegate, args);
      return ChatServiceHandler.wrap(service);
    }

    return method.invoke(delegate, args);
  }
}
