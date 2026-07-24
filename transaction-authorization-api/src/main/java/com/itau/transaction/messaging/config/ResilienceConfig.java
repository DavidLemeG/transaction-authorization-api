package com.itau.transaction.messaging.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;

/**
 * Instancias nomeadas registradas nos registries auto-configurados pelo
 * resilience4j-spring-boot3 (RetryRegistry/CircuitBreakerRegistry), para que
 * fiquem visiveis em /actuator/health, /actuator/metrics e /actuator/prometheus
 * como qualquer instancia configurada via application.yaml.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    /**
     * Retry para chamadas ao SQS (receiveMessage/deleteMessageBatch/getQueueUrl).
     * Backoff exponencial com jitter completo (full jitter): cada tentativa espera
     * um valor aleatorio entre 0 e o teto exponencial, evitando que threads em
     * lockstep martelem a fila ao mesmo tempo apos uma falha transitoria.
     */
    @Bean
    public Retry sqsRetry(RetryRegistry retryRegistry) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(5)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(500), 2.0, 0.5))
                .retryExceptions(SdkException.class)
                .build();

        Retry retry = retryRegistry.retry("sqs", config);
        retry.getEventPublisher().onRetry(event ->
                log.warn("Retry #{} na chamada SQS apos falha: {}", event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()));
        return retry;
    }

    /**
     * Circuit breaker no acesso ao banco durante o consumo da fila. Se o Postgres
     * ficar indisponivel, o circuito abre e o listener para de tentar persistir
     * contas (e, portanto, para de deletar mensagens da fila) ate o banco normalizar.
     */
    @Bean
    public CircuitBreaker dbCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(5)
                .recordExceptions(DataAccessException.class)
                .build();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("db-persistence", config);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker 'db-persistence' mudou de estado: {} -> {}",
                        event.getStateTransition().getFromState(), event.getStateTransition().getToState()));
        return circuitBreaker;
    }
}
