package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.openai.ReflectionUtils;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAITelemetry;
import com.statsig.ai.trace.StatsigTraceConventions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ChatCompletionServiceHandler implements InvocationHandler {

  private final ChatCompletionService delegate;
  private final OpenAITelemetry telemetry = new OpenAITelemetry();

  private ChatCompletionServiceHandler(ChatCompletionService delegate) {
    this.delegate = delegate;
  }

  public static ChatCompletionService wrap(ChatCompletionService delegate) {
    return (ChatCompletionService)
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {ChatCompletionService.class},
            new ChatCompletionServiceHandler(delegate));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (!"create".equals(method.getName())
        || args == null
        || args.length == 0
        || !(args[0] instanceof ChatCompletionCreateParams)) {
      return method.invoke(delegate, args);
    }

    ChatCompletionCreateParams params = (ChatCompletionCreateParams) args[0];

    return telemetry.trace(
        "openai.chat.completions",
        StatsigTraceConventions.GEN_AI_SPAN_TYPE_CHAT,
        span -> {
          OpenAIAttributes.applyRequestAttributes(span, "chat.completions", params);
          Object response = ReflectionUtils.delegateInvoke(method, delegate, args);
          OpenAIAttributes.applyResponseAttributes(span, response);
          OpenAIAttributes.applyUsageAttributes(span, response);
          return response;
        });
  }
}
