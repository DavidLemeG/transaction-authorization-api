package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova, contra um PostgreSQL real, que o lock pessimista em
 * {@link AccountRepository#findByIdForUpdate} serializa débitos concorrentes
 * na mesma conta e nunca deixa o saldo ficar negativo.
 */
@Testcontainers
@SpringBootTest
class ConcurrentDebitTest {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal DEBIT_AMOUNT = new BigDecimal("80.00");

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
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
        account.credit(INITIAL_BALANCE);
        accountRepository.saveAndFlush(account);
        accountId = account.getId();
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void apenasUmDebitoConcorrenteEAprovadoESaldoNuncaFicaNegativo() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<AuthorizationResult>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(i -> (Callable<AuthorizationResult>) () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    AuthorizationCommand command = new AuthorizationCommand(
                            UUID.randomUUID(), accountId, TransactionType.DEBIT, DEBIT_AMOUNT, "BRL");
                    return transactionService.authorize(command);
                })
                .collect(Collectors.toList());

        List<Future<AuthorizationResult>> futures = tasks.stream().map(executor::submit).collect(Collectors.toList());

        readyLatch.await();
        startLatch.countDown();

        long succeeded = 0;
        for (Future<AuthorizationResult> future : futures) {
            AuthorizationResult result = future.get();
            if (result.transaction().getStatus().name().equals("SUCCEEDED")) {
                succeeded++;
            }
        }
        executor.shutdown();

        Account finalAccount = accountRepository.findById(accountId).orElseThrow();

        assertThat(succeeded).isEqualTo(1);
        assertThat(finalAccount.getBalance()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(finalAccount.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
