# Statsig AI Java SDK

This module builds on top of the Statsig Java Core SDK to make working with prompt experiments and AI telemetry easier on the JVM. It exposes helpers to fetch prompt definitions, run user-targeted prompt logic, record evaluation grades, and (soon) instrument OpenAI client calls for tracing.

## Installation

The library is not published yet, so include it in your build the same way you consume your other Statsig modules (local source checkout, composite build, etc.). Once artifacts are published to Maven Central you will be able to add a dependency similar to:

```kotlin
implementation("com.statsig:ai-java:<version>")
```

The SDK requires Java 8+.

## Quick start

```java
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.StatsigAI;
import com.statsig.ai.StatsigInitConfig;
import com.statsig.ai.Prompt;

StatsigOptions options =
    new StatsigOptions.Builder()
        .build();

StatsigAI ai =
    StatsigAI.newShared(
            StatsigInitConfig.coreIndependent("your-server-sdk-key", options))
        .join();

StatsigUser user = new StatsigUser.Builder().setUserID("whd").build();
Prompt prompt = ai.getPrompt(user, "support_prompt");
String systemMessage = prompt.getLivePrompt().getWorkflowBody().get("value").toString();
```

### Logging evaluation grades

```java
ai.logEvalGrade(
    user,
    prompt.getLivePrompt(),
    /* score */ 0.9,
    /* grader */ "auto_rater",
    new AIEvalGradeData("session-123"));
```

### Prompt context helpers

`Prompt.withLive(...)` and `Prompt.withContext(...)` automatically push prompt and user metadata into the OpenTelemetry context. Use them when calling downstream LLM clients so future instrumentation can enrich spans with prompt identifiers.

```java
prompt.withLive(live -> {
  // Context.current() now includes statsig.llm.prompt_name/version and user identifiers
  // call OpenAI, VertexAI, etc.
  return null;
});
```

## Instrumentation (early preview)

The package `com.statsig.ai.instrumentation` will eventually host ready-to-use wrappers for popular AI SDKs. The first entry point, `OpenAIInstrumentals.wrap`, currently performs basic validation and will add tracing/exporting logic in upcoming releases.

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.statsig.ai.instrumentation.openai.OpenAIInstrumentals;

OpenAIClient rawClient = OpenAIOkHttpClient.fromEnv();
OpenAIClient wrapped = OpenAIInstrumentals.wrap(rawClient);
```

## Development

- Format & lint: `./gradlew spotlessApply`
- Tests: `./gradlew test`

The test suite boots a lightweight mock server, so expect a few Statsig shutdown warnings in the console. They are harmless.

Contributions and feedback are welcome—please file issues or PRs with reproducible steps.
