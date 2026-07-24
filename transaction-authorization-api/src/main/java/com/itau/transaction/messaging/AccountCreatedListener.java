package com.itau.transaction.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.transaction.messaging.config.SqsProperties;
import com.itau.transaction.messaging.dto.AccountCreatedEvent;
import com.itau.transaction.service.AccountService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCreatedListener {

    private static final int MAX_MESSAGES_PER_POLL = 10;
    private static final int LONG_POLL_SECONDS = 20;

    private static final int LOG_EVERY = 10_000;

    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong startedAt = new AtomicLong();
    private final AtomicBoolean summaryLogged = new AtomicBoolean(true);
    private final AtomicBoolean queueFoundLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstBatchLogged = new AtomicBoolean(false);

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final Retry sqsRetry;
    private final CircuitBreaker dbCircuitBreaker;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executor;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int threads = properties.consumerThreads();
        executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(this::runConsumer);
        }

        log.info("Consumidor SQS iniciado: {} threads na fila '{}'", threads, properties.queueName());
    }

    @PreDestroy
    public void stop() {
        log.info("Encerrando consumidor SQS...");
        running.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void runConsumer() {
        while (running.get()) {
            try {
                String queueUrl = resolveQueueUrl();
                if (queueFoundLogged.compareAndSet(false, true)) {
                    log.info("Fila '{}' encontrada; consumidor pronto para receber mensagens", properties.queueName());
                }
                pollLoop(queueUrl);
            } catch (Exception e) {
                log.error("Fila '{}' indisponivel; nova tentativa em 10s", properties.queueName(), e);
                sleepQuietly(10);
            }
        }
    }

    // Visibilidade de pacote (nao private) de proposito: permite que
    // AccountCreatedListenerTest chame estes metodos diretamente, sem
    // precisar de threads reais nem de um SqsClient/LocalStack de verdade.
    String resolveQueueUrl() {
        return Retry.decorateSupplier(sqsRetry, () -> sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(properties.queueName())
                .build()).queueUrl()).get();
    }

    private void pollLoop(String queueUrl) {
        while (running.get()) {
            try {
                List<Message> messages = receiveMessages(queueUrl);
                if (messages.isEmpty()) {
                    reportSummaryIfDrained();
                } else {
                    processBatch(queueUrl, messages);
                }
            } catch (Exception e) {
                log.error("Erro no ciclo de polling da fila; tentando novamente", e);
                sleepQuietly(10);
            }
        }
    }

    List<Message> receiveMessages(String queueUrl) {
        return Retry.decorateSupplier(sqsRetry, () -> sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                .waitTimeSeconds(LONG_POLL_SECONDS)
                .build()).messages()).get();
    }

    void processBatch(String queueUrl, List<Message> messages) {
        List<DeleteMessageBatchRequestEntry> processed = new ArrayList<>();

        for (Message message : messages) {
            if (dbCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                log.warn("Circuit breaker do banco aberto; pausando consumo ate o Postgres normalizar " +
                        "(mensagens restantes deste lote nao serao processadas nem deletadas)");
                break;
            }
            try {
                AccountCreatedEvent event = objectMapper.readValue(message.body(), AccountCreatedEvent.class);
                CircuitBreaker.decorateRunnable(dbCircuitBreaker, () -> accountService.createIfAbsent(event)).run();

                processed.add(DeleteMessageBatchRequestEntry.builder()
                        .id(message.messageId())
                        .receiptHandle(message.receiptHandle())
                        .build());
                meterRegistry.counter("sqs.messages.consumed").increment();

            } catch (CallNotPermittedException e) {
                log.warn("Circuit breaker do banco aberto; mensagem {} sera reentregue pela fila", message.messageId());
                break;
            } catch (Exception e) {
                log.error("Falha ao processar mensagem {}; será reentregue pela fila", message.messageId(), e);
                meterRegistry.counter("sqs.messages.failed").increment();
            }
        }

        if (!processed.isEmpty()) {
            deleteBatch(queueUrl, processed);
            recordProgress(processed.size());
        }
    }

    void deleteBatch(String queueUrl, List<DeleteMessageBatchRequestEntry> entries) {
        Retry.decorateRunnable(sqsRetry, () -> sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build())).run();
    }

    private void sleepQuietly(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private void recordProgress(int batchSize) {
        startedAt.compareAndSet(0, System.currentTimeMillis());
        summaryLogged.set(false);

        if (firstBatchLogged.compareAndSet(false, true)) {
            log.info("Primeiro lote recebido; iniciando processamento");
        }

        long total = processedCount.addAndGet(batchSize);
        long previous = total - batchSize;

        if (total / LOG_EVERY != previous / LOG_EVERY) {
            logThroughput("Progresso", total);
        }
    }

    void reportSummaryIfDrained() {
        if (processedCount.get() > 0 && summaryLogged.compareAndSet(false, true)) {
            logThroughput("Fila drenada", processedCount.get());
        }
    }

    private void logThroughput(String label, long total) {
        double seconds = (System.currentTimeMillis() - startedAt.get()) / 1000.0;
        double rate = seconds > 0 ? total / seconds : 0;
        log.info("{}: {} contas processadas em {}s ({} msg/s)",
                label, total, String.format("%.1f", seconds), String.format("%.0f", rate));
    }
}