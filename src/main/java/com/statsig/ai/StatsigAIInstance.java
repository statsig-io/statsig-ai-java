package com.statsig.ai;

import com.statsig.Statsig;
import com.statsig.ai.internal.StatsigIntegrationConfig;

import java.util.concurrent.CompletableFuture;

class StatsigAIInstance {
    private final Statsig statsigCoreInstance;
    private final boolean managesCoreInstance;

     StatsigAIInstance(StatsigIntegrationConfig config) {
        if (config instanceof StatsigIndependentConfig) {
            StatsigIndependentConfig standalone = (StatsigIndependentConfig) config;
            this.statsigCoreInstance = new Statsig(standalone.getSdkKey(), standalone.getStatsigOptions());
            managesCoreInstance = false;
        } else if (config instanceof StatsigCoreAttachedConfig) {
            StatsigCoreAttachedConfig integrated = (StatsigCoreAttachedConfig) config;
            this.statsigCoreInstance = integrated.getStatsigInstance();
            managesCoreInstance = true;
        } else {
            this.statsigCoreInstance = null;
            managesCoreInstance = false;
        }
    }

    CompletableFuture<Void> initialize() {
         if (managesCoreInstance && statsigCoreInstance != null) {
             return statsigCoreInstance.initialize();
         }
         return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> shutdown() {
         if (managesCoreInstance && statsigCoreInstance != null) {
             return statsigCoreInstance.shutdown();
         }
         return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> flushEvents() {
         if (managesCoreInstance && statsigCoreInstance != null) {
             return statsigCoreInstance.flushEvents();
         }
         // TODO: add otel support
         return CompletableFuture.completedFuture(null);
    }
}

