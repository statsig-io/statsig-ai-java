package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.ReflectionUtils;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public final class ChatCompletionServiceHandler implements InvocationHandler {
  private static final String SOURCE_NAME = "openai.chat.completions.create";

  private final ChatCompletionService delegate;
  private final GenAITelemetry telemetry;

  private ChatCompletionServiceHandler(ChatCompletionService delegate, GenAITelemetry telemetry) {
    this.delegate = delegate;
    this.telemetry = telemetry;
  }

  public static ChatCompletionService wrap(
      ChatCompletionService delegate, GenAITelemetry telemetry) {
    return (ChatCompletionService)
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {ChatCompletionService.class},
            new ChatCompletionServiceHandler(delegate, telemetry));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (!"create".equals(method.getName())
        || args == null
        || args.length == 0
        || !(args[0] instanceof ChatCompletionCreateParams)) {
      return method.invoke(delegate, args);
    }

    Map<String, Object> params = OpenAIAttributes.toPlainRequest(args[0]);

    return telemetry.trace(
        OpenAIAttributes.spanName(SOURCE_NAME, params),
        OpenAIAttributes.requestAttributes(SOURCE_NAME, params, telemetry.getCaptureOptions()),
        () -> ReflectionUtils.delegateInvoke(method, delegate, args),
        response ->
            OpenAIAttributes.responseAttributes(
                OpenAIAttributes.toPlainMap(response), telemetry.getCaptureOptions()));
  }
}
