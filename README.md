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

## Offline evals

`Eval` runs a task on each record of a dataset, scores each output, and sends the results to Statsig as a new run of an eval. Create the eval in the Statsig console first. Sending needs a Statsig Console API key, from the `STATSIG_API_KEY` environment variable or `apiKey(...)`.

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.statsig.ai.evals.Eval;
import com.statsig.ai.evals.EvalDataRecord;
import com.statsig.ai.evals.EvalResult;
import com.statsig.ai.evals.EvalResultRecord;
import com.statsig.ai.instrumentation.openai.OpenAIWrapper;
import java.util.Arrays;
import java.util.List;

OpenAIClient openai = OpenAIWrapper.wrap(OpenAIOkHttpClient.fromEnv());

// Input, expected output, and an optional category.
List<EvalDataRecord<String, String>> data =
    Arrays.asList(
        EvalDataRecord.of("What is 2 + 2?", "4", "math"),
        EvalDataRecord.of("What is the capital of France?", "Paris", "geography"));

EvalResult<String, String, String> result =
    Eval.builder("trivia_bot", data)
        .task(
            (input, hooks) -> {
              String model = hooks.getParameter("model");
              ChatCompletion completion =
                  openai
                      .chat()
                      .completions()
                      .create(
                          ChatCompletionCreateParams.builder()
                              .model(model)
                              .addSystemMessage("Reply with only the answer.")
                              .addUserMessage(input)
                              .build());
              return completion.choices().get(0).message().content().orElse("");
            })
        .scorer(args -> args.getOutput().trim().equalsIgnoreCase(args.getExpected()))
        .parameter("model", "gpt-4o-mini")
        .evalRunName("gpt-4o-mini baseline")
        .build()
        .run();

for (EvalResultRecord<String, String, String> record : result.getResults()) {
  System.out.println(record.getInput() + " -> " + record.getOutput() + " " + record.getScores());
}
```

- A scorer returns a `Number`, a `Boolean` (true is 1, false is 0), or a `ScoreWithMetadata` to attach notes such as a judge's reasoning. Anything else, or a throw, scores 0 and logs a warning.
- `scorer(args -> ...)` names the scorer `Grader`. Add more with `scorer(name, ...)` or `scorers(map)`.
- If the task throws, the record is an error: every score is 0, `getOutput()` is null, Statsig gets `[Error]` as the output, and `result.isError()` is true.
- Use `asyncTask(...)` for a task that returns a `CompletableFuture`, and `runAsync()` to get the results as a `CompletableFuture`.
- Records run concurrently, and so do a record's scorers. Each run uses a pool of min(32, processors + 4) threads unless you pass `executor(...)`.
- `summaryScores(results -> ...)` adds run-level scores, such as a pass rate.
- Inputs, outputs, expected values, parameters and metadata values that aren't strings are sent as JSON. If the results can't be sent, `run()` logs a warning and still returns them.

## Development

- Format & lint: `./gradlew spotlessApply`
- Tests: `./gradlew test`

The test suite boots a lightweight mock server, so expect a few Statsig shutdown warnings in the console. They are harmless.

Contributions and feedback are welcome—please file issues or PRs with reproducible steps.
