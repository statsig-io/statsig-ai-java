package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.client.OpenAIClient;
import com.openai.services.blocking.ChatService;
import com.statsig.ai.instrumentation.GenAITelemetry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public final class ClientHandler implements InvocationHandler {
  private final OpenAIClient delegate;
  private final GenAITelemetry telemetry;

  public ClientHandler(OpenAIClient delegate, GenAITelemetry telemetry) {
    this.delegate = delegate;
    this.telemetry = telemetry;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    if (method.getDeclaringClass() == Object.class) {
      return method.invoke(delegate, args);
    }

    if ("chat".equals(method.getName())) {
      ChatService service = (ChatService) method.invoke(delegate, args);
      return ChatServiceHandler.wrap(service, telemetry);
    }

    return method.invoke(delegate, args);
  }
}
