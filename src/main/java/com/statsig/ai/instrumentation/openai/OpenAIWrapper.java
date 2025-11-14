package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.statsig.ai.instrumentation.openai.handlers.ClientHandler;

import java.lang.reflect.Proxy;

public final class OpenAIWrapper {
    private OpenAIWrapper() {}

    public static OpenAIClient wrap(OpenAIClient client) {
        return (OpenAIClient) Proxy.newProxyInstance(
                client.getClass().getClassLoader(),
                new Class<?>[]{OpenAIClient.class},
                new ClientHandler(client)
        );
    }
}
