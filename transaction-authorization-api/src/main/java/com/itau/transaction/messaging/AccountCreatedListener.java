package com.itau.transaction.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.transaction.messaging.config.SqsProperties;
import com.itau.transaction.messaging.dto.AccountCreatedEvent;
import com.itau.transaction.service.AccountService;
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

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;

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
                pollLoop(queueUrl);
            } catch (Exception e) {
                log.error("Fila '{}' indisponivel; nova tentativa em 10s", properties.queueName(), e);
                sleepQuietly(10);
            }
        }
    }

    private String resolveQueueUrl() {
        return sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(properties.queueName())
                .build()).queueUrl();
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

    private List<Message> receiveMessages(String queueUrl) {
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                .waitTimeSeconds(LONG_POLL_SECONDS)
                .build()).messages();
    }

    private void processBatch(String queueUrl, List<Message> messages) {
        List<DeleteMessageBatchRequestEntry> processed = new ArrayList<>();

        for (Message message : messages) {
            try {
                AccountCreatedEvent event = objectMapper.readValue(message.body(), AccountCreatedEvent.class);
                accountService.createIfAbsent(event);

                processed.add(DeleteMessageBatchRequestEntry.builder()
                        .id(message.messageId())
                        .receiptHandle(message.receiptHandle())
                        .build());

            } catch (Exception e) {
                log.error("Falha ao processar mensagem {}; será reentregue pela fila", message.messageId(), e);
            }
        }

        if (!processed.isEmpty()) {
            sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(processed)
                    .build());
            recordProgress(processed.size());
        }
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

        long total = processedCount.addAndGet(batchSize);
        long previous = total - batchSize;

        if (total / LOG_EVERY != previous / LOG_EVERY) {
            logThroughput("Progresso", total);
        }
    }

    private void reportSummaryIfDrained() {
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