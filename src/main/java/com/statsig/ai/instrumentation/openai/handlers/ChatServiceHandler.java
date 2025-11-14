package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ChatServiceHandler implements InvocationHandler {
  private final ChatService delegate;

  private ChatServiceHandler(ChatService delegate) {
    this.delegate = delegate;
  }

  public static ChatService wrap(ChatService service) {
    return (ChatService)
        Proxy.newProxyInstance(
            service.getClass().getClassLoader(),
            new Class<?>[] {ChatService.class},
            new ChatServiceHandler(service));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if ("completions".equals(method.getName())) {
      ChatCompletionService completions = (ChatCompletionService) method.invoke(delegate, args);
      return ChatCompletionServiceHandler.wrap(completions);
    }

    return method.invoke(delegate, args);
  }
}
