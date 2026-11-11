package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.instrumentation.openai.ReflectionUtils;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAITelemetry;
import com.statsig.ai.trace.StatsigTraceConventions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ChatCompletionServiceHandler implements InvocationHandler {

  private final ChatCompletionService delegate;
  private final GenAICaptureOptions captureOptions;
  private final OpenAITelemetry telemetry;

  private ChatCompletionServiceHandler(ChatCompletionService delegate, OpenAIWrapperConfig config) {
    this.delegate = delegate;
    this.captureOptions = config.getCaptureOptions();
    this.telemetry = new OpenAITelemetry(config);
  }

  public static ChatCompletionService wrap(
      ChatCompletionService delegate, OpenAIWrapperConfig config) {
    return (ChatCompletionService)
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {ChatCompletionService.class},
            new ChatCompletionServiceHandler(delegate, config));
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
          OpenAIAttributes.applyRequestAttributes(span, "chat.completions", params, captureOptions);
          Object response = ReflectionUtils.delegateInvoke(method, delegate, args);
          OpenAIAttributes.applyResponseAttributes(span, response, captureOptions);
          OpenAIAttributes.applyUsageAttributes(span, response);
          return response;
        });
  }
}
