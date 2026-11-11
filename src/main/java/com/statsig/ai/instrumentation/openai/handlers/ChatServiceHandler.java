package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.GenAITelemetry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ChatServiceHandler implements InvocationHandler {
  private final ChatService delegate;
  private final GenAITelemetry telemetry;

  private ChatServiceHandler(ChatService delegate, GenAITelemetry telemetry) {
    this.delegate = delegate;
    this.telemetry = telemetry;
  }

  public static ChatService wrap(ChatService service, GenAITelemetry telemetry) {
    return (ChatService)
        Proxy.newProxyInstance(
            service.getClass().getClassLoader(),
            new Class<?>[] {ChatService.class},
            new ChatServiceHandler(service, telemetry));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if ("completions".equals(method.getName())) {
      ChatCompletionService completions = (ChatCompletionService) method.invoke(delegate, args);
      return ChatCompletionServiceHandler.wrap(completions, telemetry);
    }

    return method.invoke(delegate, args);
  }
}
