package com.statsig.ai;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.ai.internal.StatsigIntegrationConfig;

/**
 * A configuration class used when initializing a Statsig AI SDK instance
 * that should attach to an existing Java Core instance.
 *
 * <p>This mode allows the SDK to reuse an already initialized
 * core instance for evaluation and event logging, instead of creating
 * its own internal core. This is useful when multiple SDK layers
 * (for example, AI SDK and Core SDK) share the same underlying resources.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * }</pre>
 */
public class StatsigCoreAttachedConfig implements StatsigIntegrationConfig {
    private Statsig statsigCoreInstance;
    private StatsigOptions statsigOptions;

    public StatsigCoreAttachedConfig() {}

    public StatsigCoreAttachedConfig(Statsig statsigCoreInstance, StatsigOptions statsigOptions) {
        this.statsigCoreInstance = statsigCoreInstance;
    }

    public void setStatsigInstance(Statsig statsigInstance) {
        this.statsigCoreInstance = statsigInstance;
    }

    public void setStatsigOptions(StatsigOptions statsigOptions) {
        this.statsigOptions = statsigOptions;
    }

    public Statsig getStatsigInstance() {
        return statsigCoreInstance;
    }
    public StatsigOptions getStatsigOptions() {
        return statsigOptions;
    }
}
