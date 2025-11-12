package com.statsig.ai.trace;

import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

/**
 * Responsible for initializing the OTEL pipeline (exporter, processor, provider)
 * and registering the global instance via OtelSingleton.
 */
public class Otel {
    public static void initialize(InitializeTracingOptions options) {
        StatsigOTLPTraceExporterOptions exporterOptions = options.getExporterOptions();
        StatsigOTLPTraceExporter exporter = new StatsigOTLPTraceExporter(exporterOptions);

        // TODO Use BatchSpanProcessor for better performance
        SpanProcessor spanProcessor = BatchSpanProcessor.builder(exporter).build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(spanProcessor)
            .setSampler(Sampler.alwaysOn())
            .build();

        OtelSingleton.instantiate(tracerProvider);


        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();

        System.out.println("[Statsig OTEL] Initialized with exporter: " + exporterOptions.getEndpoint());
    }
}

