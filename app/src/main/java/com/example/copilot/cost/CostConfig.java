package com.example.copilot.cost;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for Phase 4 slice 1 per-request token + cost accounting.
 *
 * <p>The handler is registered via an {@link ObservationRegistryCustomizer}
 * rather than direct injection of the {@link ObservationRegistry} into the
 * @Bean method. Direct injection causes a circular dependency: Boot's
 * ObservationRegistry is consumed by webMvcObservationFilter, and any
 * ObservationHandler bean that depends on the registry forms a cycle with
 * it. The customizer is applied during registry initialization, before
 * other beans see the registry.
 */
@Configuration
@EnableConfigurationProperties(CostProperties.class)
public class CostConfig {

    /**
     * Handler is constructed inside the customizer rather than exposed as a
     * separate @Bean. Exposing it as a bean caused it to be auto-registered
     * with the {@link ObservationRegistry} a SECOND time (Boot picks up
     * ObservationHandler beans automatically), which made {@code onStop}
     * fire twice per call and double the reported cost.
     */
    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> costHandlerRegistration(
            CostProperties costProps, MeterRegistry meters) {
        CostObservationHandler handler = new CostObservationHandler(costProps, meters);
        return registry -> registry.observationConfig().observationHandler(handler);
    }

    @Bean
    public CostRequestFilter costRequestFilter(Tracer tracer) {
        return new CostRequestFilter(tracer);
    }
}
