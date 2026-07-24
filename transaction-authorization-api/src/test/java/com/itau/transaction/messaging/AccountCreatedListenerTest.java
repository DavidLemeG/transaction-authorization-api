package com.itau.transaction.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.transaction.messaging.config.SqsProperties;
import com.itau.transaction.service.AccountService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testa AccountCreatedListener sem threads reais nem AWS/LocalStack de
 * verdade: SqsClient e AccountService sao mocks, mas Retry e CircuitBreaker
 * sao instancias REAIS do Resilience4j (configuradas para serem rapidas em
 * teste), para provar que a resiliencia esta de fato ligada -- nao so
 * presente no classpath.
 */
@ExtendWith(MockitoExtension.class)
class AccountCreatedListenerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/conta-bancaria-criada";

    @Mock
    private SqsClient sqsClient;

    @Mock
    private AccountService accountService;

    private AccountCreatedListener listener;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        SqsProperties properties = new SqsProperties(
                "sa-east-1", "http://localhost:4566", "test", "test", "conta-bancaria-criada", 4);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(SdkException.class)
                .build();
        Retry retry = Retry.of("test-sqs", retryConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50)
                .recordExceptions(RuntimeException.class)
                .build();
        circuitBreaker = CircuitBreaker.of("test-db", cbConfig);

        listener = new AccountCreatedListener(
                sqsClient, properties, accountService, new ObjectMapper(), retry, circuitBreaker, new SimpleMeterRegistry());
    }

    private Message validMessage(String id) {
        UUID accountId = UUID.randomUUID();
        String body = String.format(
                "{\"account\":{\"id\":\"%s\",\"owner\":\"%s\",\"created_at\":\"1700000000\",\"status\":\"ENABLED\"}}",
                accountId, UUID.randomUUID());
        return Message.builder().messageId(id).receiptHandle("rh-" + id).body(body).build();
    }

    private Message malformedMessage(String id) {
        return Message.builder().messageId(id).receiptHandle("rh-" + id).body("{ isso nao e json valido").build();
    }

    // --- resolveQueueUrl ---

    @Test
    void resolveQueueUrlRetornaUrlNaPrimeiraTentativa() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());

        String result = listener.resolveQueueUrl();

        assertThat(result).isEqualTo(QUEUE_URL);
        verify(sqsClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void resolveQueueUrlTentaDeNovoAposFalhaTransitoriaEDepoisConsegue() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenThrow(SdkException.builder().message("timeout transitorio").build())
                .thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());

        String result = listener.resolveQueueUrl();

        assertThat(result).isEqualTo(QUEUE_URL);
        verify(sqsClient, times(2)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    // --- receiveMessages ---

    @Test
    void receiveMessagesRetornaMensagensDaFila() {
        Message message = validMessage("m1");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());

        List<Message> result = listener.receiveMessages(QUEUE_URL);

        assertThat(result).containsExactly(message);
    }

    @Test
    void receiveMessagesTentaDeNovoAposFalhaTransitoria() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenThrow(SdkException.builder().message("timeout transitorio").build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        List<Message> result = listener.receiveMessages(QUEUE_URL);

        assertThat(result).isEmpty();
        verify(sqsClient, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    // --- deleteBatch ---

    @Test
    void deleteBatchChamaSqsComQueueUrlEEntriesCorretos() {
        Message message = validMessage("m1");
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        listener.processBatch(QUEUE_URL, List.of(message));

        ArgumentCaptor<DeleteMessageBatchRequest> captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().entries()).hasSize(1);
        assertThat(captor.getValue().entries().get(0).id()).isEqualTo("m1");
    }

    // --- processBatch ---

    @Test
    void mensagemValidaECriaContaEDeletaDaFila() {
        Message message = validMessage("m1");
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        listener.processBatch(QUEUE_URL, List.of(message));

        verify(accountService, times(1)).createIfAbsent(any());
        verify(sqsClient, times(1)).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void mensagemComJsonInvalidoNaoCriaContaNaoDeletaENaoPropagaExcecao() {
        Message message = malformedMessage("m1");

        listener.processBatch(QUEUE_URL, List.of(message));

        verify(accountService, never()).createIfAbsent(any());
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void loteMistoSoDeletaAMensagemQueDeuCerto() {
        Message boa = validMessage("m1");
        Message ruim = malformedMessage("m2");
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());

        listener.processBatch(QUEUE_URL, List.of(ruim, boa));

        verify(accountService, times(1)).createIfAbsent(any());
        ArgumentCaptor<DeleteMessageBatchRequest> captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());
        assertThat(captor.getValue().entries()).hasSize(1);
        assertThat(captor.getValue().entries().get(0).id()).isEqualTo("m1");
    }

    @Test
    void circuitBreakerJaAbertoIgnoraOLoteInteiroSemChamarAccountService() {
        circuitBreaker.transitionToOpenState();
        Message message = validMessage("m1");

        listener.processBatch(QUEUE_URL, List.of(message));

        verify(accountService, never()).createIfAbsent(any());
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void circuitBreakerAbreNoMeioDoLoteEPulaAsMensagensRestantes() {
        Message primeira = validMessage("m1");
        Message segunda = validMessage("m2");
        // A primeira chamada falha -- com minimumNumberOfCalls=1 e
        // failureRateThreshold=50, isso e suficiente para abrir o circuito.
        org.mockito.Mockito.doThrow(new RuntimeException("banco fora do ar"))
                .when(accountService).createIfAbsent(any());

        listener.processBatch(QUEUE_URL, List.of(primeira, segunda));

        verify(accountService, times(1)).createIfAbsent(any());
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void reportSummaryIfDrainedNaoLancaExcecaoQuandoNadaFoiProcessadoAinda() {
        listener.reportSummaryIfDrained();
    }
}
