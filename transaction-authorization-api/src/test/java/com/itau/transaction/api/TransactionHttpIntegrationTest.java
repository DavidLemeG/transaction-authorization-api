package com.itau.transaction.api;

import com.itau.transaction.api.dto.TransactionRequest;
import com.itau.transaction.api.dto.TransactionResponse;
import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unico teste de integracao HTTP de ponta a ponta do projeto: requisicao
 * HTTP real -> TransactionController -> TransactionService/TransactionRecorder
 * -> AccountRepository/TransactionRepository -> PostgreSQL real (Testcontainers).
 * Diferente de TransactionControllerTest (slice test com o service mockado),
 * aqui nada e mockado -- cobre os 3 cenarios do enunciado exatamente como um
 * cliente real chamaria a API.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionHttpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banking")
            .withUsername("itau")
            .withPassword("itau");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
        accountRepository.saveAndFlush(account);
        accountId = account.getId();
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void creditarDiretoNoBanco(BigDecimal amount) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.credit(amount);
        accountRepository.saveAndFlush(account);
    }

    private TransactionRequest requestFor(TransactionType type, String value, String currency) {
        return new TransactionRequest(accountId, type, new TransactionRequest.Amount(new BigDecimal(value), currency));
    }

    @Test
    void creditoAprovadoViaHttp() {
        UUID transactionId = UUID.randomUUID();

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                "/transactions/" + transactionId, requestFor(TransactionType.CREDIT, "97.07", "BRL"),
                TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().transaction().status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(response.getBody().transaction().amount().value()).isEqualByComparingTo("97.07");
        assertThat(response.getBody().account().balance().amount()).isEqualByComparingTo("97.07");

        Account persisted = accountRepository.findById(accountId).orElseThrow();
        assertThat(persisted.getBalance()).isEqualByComparingTo("97.07");
        assertThat(transactionRepository.findById(transactionId)).isPresent();
    }

    @Test
    void debitoAprovadoViaHttp() {
        creditarDiretoNoBanco(new BigDecimal("100.00"));
        UUID transactionId = UUID.randomUUID();

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                "/transactions/" + transactionId, requestFor(TransactionType.DEBIT, "40.00", "BRL"),
                TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().transaction().status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(response.getBody().account().balance().amount()).isEqualByComparingTo("60.00");

        Account persisted = accountRepository.findById(accountId).orElseThrow();
        assertThat(persisted.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitoRecusadoPorSaldoInsuficienteViaHttp() {
        creditarDiretoNoBanco(new BigDecimal("30.00"));
        UUID transactionId = UUID.randomUUID();

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                "/transactions/" + transactionId, requestFor(TransactionType.DEBIT, "30.01", "BRL"),
                TransactionResponse.class);

        // Debito recusado ainda e HTTP 200 (ADR 0009): a requisicao foi processada,
        // a decisao de negocio foi recusar.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().transaction().status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(response.getBody().transaction().reason()).isNotBlank();
        assertThat(response.getBody().account().balance().amount()).isEqualByComparingTo("30.00");

        Account persisted = accountRepository.findById(accountId).orElseThrow();
        assertThat(persisted.getBalance())
                .as("saldo nao pode ser alterado quando o debito e recusado")
                .isEqualByComparingTo("30.00");
    }

    @Test
    void reenvioDoMesmoTransactionIdViaHttpNaoReprocessa() {
        creditarDiretoNoBanco(new BigDecimal("100.00"));
        UUID transactionId = UUID.randomUUID();
        TransactionRequest request = requestFor(TransactionType.DEBIT, "40.00", "BRL");

        ResponseEntity<TransactionResponse> primeira = restTemplate.postForEntity(
                "/transactions/" + transactionId, request, TransactionResponse.class);
        ResponseEntity<TransactionResponse> segunda = restTemplate.postForEntity(
                "/transactions/" + transactionId, request, TransactionResponse.class);

        assertThat(primeira.getBody().transaction().id()).isEqualTo(segunda.getBody().transaction().id());
        assertThat(segunda.getBody().account().balance().amount()).isEqualByComparingTo("60.00");

        Account persisted = accountRepository.findById(accountId).orElseThrow();
        assertThat(persisted.getBalance())
                .as("reenviar o mesmo transactionId nao pode debitar duas vezes")
                .isEqualByComparingTo("60.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void contaInexistenteRetorna404ViaHttp() {
        UUID contaQueNaoExiste = UUID.randomUUID();
        TransactionRequest request = new TransactionRequest(
                contaQueNaoExiste, TransactionType.CREDIT, new TransactionRequest.Amount(new BigDecimal("10.00"), "BRL"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/transactions/" + UUID.randomUUID(), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
