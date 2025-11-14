package com.statsig.ai.instrumentation.openai;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class ReflectionUtils {
  private ReflectionUtils() {}

  public static Object delegateInvoke(Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause(); // unwrap
    }
  }
}
