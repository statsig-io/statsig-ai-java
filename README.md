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
String systemMessage = prompt.getLive().getWorkflowBody().get("value").toString();
```

`newShared` keeps an existing shared instance: a second call logs a warning and returns an invalid instance that makes no network calls. To create another shared instance, call `StatsigAI.removeSharedInstance()` first. It doesn't shut the old instance down.

### Logging evaluation grades

```java
ai.logEvalGrade(
    user,
    prompt.getLive(),
    /* score */ 0.9,
    /* grader */ "auto_rater",
    new AIEvalGradeData("session-123"));
```

### Agent configs

`getAgentConfig` returns an agent's live version and its candidates for a user, from the agent's `agent:<name>` parameter store. Each version is a DAG of prompts, and `getRoot()` returns its root prompt as a `PromptVersion`. The live version's root prompt is live for the user, and the candidates' aren't, so `logEvalGrade` records the right `is_live`.

```java
import com.statsig.ai.agents.AgentConfig;
import com.statsig.ai.agents.AgentVersion;

AgentConfig agent = ai.getAgentConfig(user, "support_agent");
PromptVersion root = agent.getLive().getRoot();
String model = root.getModel("gpt-4o-mini");

for (AgentVersion candidate : agent.getCandidates()) {
  System.out.println(candidate.getRoot().getName());
}
```

For an agent that doesn't exist, the live version's root prompt is empty and there are no candidates.

## Instrumentation (early preview)

The package `com.statsig.ai.instrumentation` will eventually host ready-to-use wrappers for popular AI SDKs. The first entry point, `OpenAIWrapper.wrap`, records each `chat().completions().create(...)` call as an OpenTelemetry span. Spans have the same names and attributes as the Node SDK's: the span is named `<operation> <model>`, such as `chat gpt-4o`, with `gen_ai.operation.name`, `gen_ai.provider.name`, the request settings, token usage, and `statsig.gen_ai.server.time_to_first_token_ms`. Messages use the [OpenTelemetry GenAI format](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/), `{role, parts}`.

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.statsig.ai.instrumentation.openai.OpenAIWrapper;

OpenAIClient rawClient = OpenAIOkHttpClient.fromEnv();
OpenAIClient wrapped = OpenAIWrapper.wrap(rawClient);
```

### Capturing message content

Messages usually hold user input and model output, so by default spans don't record them. To record them, pass an `OpenAIWrapperConfig` with `GenAICaptureOptions`:

| Option | Records |
| --- | --- |
| `captureInputMessages` | `gen_ai.input.messages` |
| `captureOutputMessages` | `gen_ai.output.messages` |
| `captureSystemInstructions` | `gen_ai.system_instructions` |
| `captureToolDefinitions` | `gen_ai.tool.definitions` |
| `captureAll` | all four |

```java
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import java.util.Collections;

OpenAIClient wrapped =
    OpenAIWrapper.wrap(
        rawClient,
        OpenAIWrapperConfig.builder()
            .setCaptureOptions(
                GenAICaptureOptions.builder()
                    .captureInputMessages(true)
                    .captureOutputMessages(true)
                    .build())
            .setCustomAttributes(Collections.singletonMap("app.team", "support"))
            .build());
```

`setCustomAttributes` adds your own attributes to every model call span; the wrapper's attributes win when a key is the same. JSON attributes, such as the messages, are truncated after `setMaxJsonChars` characters (40,000 by default), and `<key>_len` records each one's full length.

### Tagging calls with a prompt version

Run the code that sends a prompt inside `prompt.withLive(...)`, or `prompt.withVersion(version, ...)` for a Candidate. Model call spans from `OpenAIWrapper` inside it, and their `statsig::gen_ai` events, get `statsig.llm.prompt_name`, `statsig.llm.prompt_version` and `statsig.llm.prompt_version_id`, which Statsig uses to attribute online eval grades to a version. Both also put the prompt's user in context, like `withStatsigUserContext`.

```java
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.statsig.ai.PromptVersion;

Prompt prompt = ai.getPrompt(user, "support_bot");
PromptVersion live = prompt.getLive();

// openai is a client from OpenAIWrapper.wrap.
ChatCompletion completion =
    prompt.withLive(
        () ->
            openai
                .chat()
                .completions()
                .create(
                    ChatCompletionCreateParams.builder()
                        .model(live.getModel())
                        .addUserMessage(input)
                        .build()));

// Try a Candidate on the same input.
PromptVersion candidate = prompt.getCandidates()[0];
ChatCompletion candidateCompletion =
    prompt.withVersion(
        candidate,
        () ->
            openai
                .chat()
                .completions()
                .create(
                    ChatCompletionCreateParams.builder()
                        .model(candidate.getModel())
                        .addUserMessage(input)
                        .build()));
```

An inner `withVersion` replaces the outer version. `prompt.withContext(...)` sets only the prompt name and the user, for code that runs before a version is chosen, and keeps any version already in context.

Template variables (`statsig.llm.prompt_template_variables`) aren't recorded yet, since the Java SDK doesn't render prompt templates.

## Tracing

`Otel.initialize` sets up a tracer provider that exports spans to Statsig.

```java
import com.statsig.ai.trace.Otel;
import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;

Otel.initialize(
    InitializeTracingOptions.builder()
        .setExporterOptions(new StatsigOTLPTraceExporterOptions("your-server-sdk-key"))
        .setServiceName("support-bot")
        .setVersion("1.4.0")
        .setEnvironment("production")
        .build());
```

- `setServiceName` and `setVersion` are recorded on the tracer provider's resource as `service.name` and `service.version`, and on each `statsig::gen_ai` event as the `service` and `version` custom IDs. Without a service name, the `OTEL_SERVICE_NAME` environment variable is used, then `SERVICE_NAME`.
- The tracer provider isn't registered as the global OpenTelemetry, so it doesn't replace your app's own. To export spans from other instrumentation that uses `GlobalOpenTelemetry` too, call `setEnableGlobalTraceProviderRegistration(true)`.

The context helpers below are static methods on `com.statsig.ai.trace.StatsigContextUtils`. Each one runs a `Supplier` and returns its result, or runs a `Runnable`. The overloads that take a `Context` return the new context instead, for code that manages its own scopes.

### Statsig context

Run code inside `withStatsigContext(user, activityID, ...)` to attribute the model calls it makes. Model call spans from `OpenAIWrapper`, and their `statsig::gen_ai` events, get `statsig.user_id` and `statsig.custom_ids` (the user's custom IDs as JSON). The activity ID is added to the custom IDs as `statsig.activity_id`. Pass a null user when there's only an activity ID.

```java
import static com.statsig.ai.trace.StatsigContextUtils.withStatsigContext;

ChatCompletion completion =
    withStatsigContext(user, "conversation-123", () -> openai.chat().completions().create(params));
```

To tag every span with the user, not only model calls, run the code inside `withStatsigUserContext(user, ...)`. The span processor that `Otel.initialize` installs adds `statsig.user_id` and `statsig.custom_ids` to each span started inside it. `Prompt.withLive(...)`, `Prompt.withVersion(...)` and `Prompt.withContext(...)` do the same for the prompt's user.

### Marking the environment

The `environment` option of `InitializeTracingOptions` is recorded on the resource as `deployment.environment.name` and `env`. To tag only some spans, for example evals running in the same process as production code, run that code inside `withStatsigEnvironment`. Spans started inside it get a `statsig.environment` attribute, which overrides the resource value for those spans.

Use `production` for live traffic. `eval` and `online_eval_judge` are reserved for spans Statsig generates.

```java
import static com.statsig.ai.trace.StatsigContextUtils.withStatsigEnvironment;

withStatsigEnvironment("staging_canary", () -> handleRequest(request));
```

### Workflow and tool spans

`GenAISpans.wrap` records a span each time the wrapped code runs, so the model calls it makes appear as its children in the trace. Use an `invoke_workflow` span for an agent turn and an `execute_tool` span for each tool call. Build the options with `WrapOptions.workflow(name)` or `WrapOptions.tool(name)`. `setUser` and `setActivityID` run the span in a Statsig context, like `withStatsigContext`, and `setAttribute` adds your own attributes.

```java
import static com.statsig.ai.trace.GenAISpans.wrap;

import com.statsig.ai.trace.options.WrapOptions;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

Function<String, List<Doc>> searchDocs =
    wrap(
        WrapOptions.tool("search_docs").setToolType("retrieval").build(),
        (String query) -> docIndex.search(query));

Supplier<ChatCompletion> supportTurn =
    wrap(
        WrapOptions.workflow("support_turn").setUser(user).setActivityID(conversationID).build(),
        () -> {
          List<Doc> docs = searchDocs.apply(input);
          return openai.chat().completions().create(buildParams(docs, input));
        });

ChatCompletion completion = prompt.withLive(supportTurn);
```

`wrap` takes a `Supplier`, `Function` or `Runnable` and returns the same type. `wrapCallable` takes a `Callable` and re-throws its checked exceptions unchanged. Each call records one span with `statsig.span.type=gen_ai`, `statsig.span.gen_ai.type` (`workflow` or `tool`), the name as `statsig.gen_ai.workflow.name` or `statsig.gen_ai.tool.name`, and the tool type as `gen_ai.tool.type`. Like model call spans, it gets the user, environment and prompt version from the context, and logs a `statsig::gen_ai` event. A span whose code throws is marked ERROR, and the exception is re-thrown. When the code returns a `CompletableFuture`, the span ends when the future completes, and `wrap` returns a future that completes with the same result after the span has ended, so you can `join()` it and then flush. Other `CompletionStage` types are returned as they are.

`startWorkflow` runs its body right away in a workflow span, and returns the result. `startWorkflowCallable` does the same for a `Callable`.

```java
import static com.statsig.ai.trace.GenAISpans.startWorkflow;

String answer = startWorkflow("support_turn", () -> runAgent(input));

startWorkflow(
    WrapOptions.workflow("nightly_digest").setUser(user).setAttribute("digest.size", 20).build(),
    () -> sendDigest(user));
```

`statsig.span.llm_root=1` marks the span that stands for a whole LLM interaction:

- A model call from `OpenAIWrapper` is the root when there's no workflow around it, including inside a tool span on its own.
- A model call inside a workflow span from `wrap` isn't, since the workflow stands for the whole turn. The `wrap` workflow span isn't marked either.
- `startWorkflow` marks its own span as the root, and the model calls inside it aren't.

### Joining a Statsig eval trace

A workflow eval sends each dataset row to your endpoint as a POST request. To show your agent's tool and model calls under the eval row in the trace drawer, run the handler inside `withIncomingTraceContext`. It reads the W3C `traceparent` and `tracestate` request headers, so spans started inside it become children of the eval row's span. Pass the headers as a `Map<String, String>` (names are matched case-insensitively) or as a header getter such as a servlet request's `getHeader`. If there's no valid `traceparent`, the handler runs as usual.

```java
import static com.statsig.ai.trace.StatsigContextUtils.withIncomingTraceContext;

// Servlet
@Override
protected void doPost(HttpServletRequest request, HttpServletResponse response)
    throws IOException {
  String input = request.getReader().lines().collect(Collectors.joining("\n"));
  String output = withIncomingTraceContext(request::getHeader, () -> runAgent(input));
  response.getWriter().write(output);
}

// Spring
@PostMapping("/agent")
public AgentResponse agent(@RequestHeader HttpHeaders headers, @RequestBody AgentRequest body) {
  return withIncomingTraceContext(headers::getFirst, () -> runAgent(body));
}
```

If your server already uses OpenTelemetry HTTP instrumentation, such as the OpenTelemetry Java agent, its spans join the trace on their own. The helper keeps the current span when it's already in the incoming trace, so using both is safe.

What Statsig sends: workflow eval requests carry `Content-Type: application/json`, the headers you configured for the workflow, and two headers of their own. `traceparent` names the eval row's root span, and `x-statsig-eval-run` holds the eval run ID. A header you configure with the same name wins, matched case-insensitively. Test Connection doesn't send either header.

Spans from your endpoint get your app's environment, so eval requests land with your production traces, where online evals can grade them. To keep them out, check for `x-statsig-eval-run` and run the handler inside `withStatsigEnvironment("eval", ...)`. You can use the same check to skip side effects.

```java
import static com.statsig.ai.trace.StatsigContextUtils.withIncomingTraceContext;
import static com.statsig.ai.trace.StatsigContextUtils.withStatsigEnvironment;

@PostMapping("/agent")
public AgentResponse agent(@RequestHeader HttpHeaders headers, @RequestBody AgentRequest body) {
  return withIncomingTraceContext(
      headers::getFirst,
      () ->
          headers.getFirst("x-statsig-eval-run") != null
              ? withStatsigEnvironment("eval", () -> runAgent(body))
              : runAgent(body));
}
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
