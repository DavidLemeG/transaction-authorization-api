package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A gravacao de fato (persist da Transaction, mutacao do saldo) mora em
 * TransactionRecorder (ver TransactionRecorderTest) -- aqui testamos apenas a
 * orquestracao do TransactionService: deteccao de idempotencia (reenvio
 * sequencial e corrida concorrente) e propagacao do resultado do recorder.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionRecorder transactionRecorder;

    private TransactionService transactionService;

    private UUID accountId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(
                accountRepository, transactionRepository, transactionRecorder, new SimpleMeterRegistry());
        accountId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
    }

    private Account accountWithBalance(BigDecimal balance) {
        Account account = new Account(accountId, UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            account.credit(balance);
        }
        return account;
    }

    private Transaction transactionFor(TransactionType type, TransactionStatus status,
                                        BigDecimal amount, BigDecimal previousBalance,
                                        BigDecimal newBalance, String reason) {
        return new Transaction(transactionId, accountId, type, amount, "BRL", status, reason,
                previousBalance, newBalance);
    }

    @Test
    void contaInexistenteLancaAccountNotFoundException() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.empty());
        when(transactionRecorder.recordNew(any())).thenThrow(new AccountNotFoundException(accountId));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("10.00"), "BRL");

        assertThatThrownBy(() -> transactionService.authorize(command))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void creditoResultaEmStatusSucceededESaldoAumentado() {
        Account account = accountWithBalance(new BigDecimal("75.00"));
        Transaction tx = transactionFor(TransactionType.CREDIT, TransactionStatus.SUCCEEDED,
                new BigDecimal("25.00"), new BigDecimal("50.00"), new BigDecimal("75.00"), null);

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.empty());
        when(transactionRecorder.recordNew(any())).thenReturn(new AuthorizationResult(tx, account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("25.00"), "BRL");

        AuthorizationResult result = transactionService.authorize(command);

        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    void debitoSemSaldoResultaEmFailedComReasonPreenchido() {
        Account account = accountWithBalance(new BigDecimal("30.00"));
        Transaction tx = transactionFor(TransactionType.DEBIT, TransactionStatus.FAILED,
                new BigDecimal("30.01"), new BigDecimal("30.00"), new BigDecimal("30.00"),
                "Debito recusado: saldo insuficiente.");

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.empty());
        when(transactionRecorder.recordNew(any())).thenReturn(new AuthorizationResult(tx, account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("30.01"), "BRL");

        AuthorizationResult result = transactionService.authorize(command);

        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.transaction().getReason()).isNotBlank();
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void reenvioDoMesmoTransactionIdRetornaTransacaoExistenteSemReprocessar() {
        Account account = accountWithBalance(new BigDecimal("100.00"));
        Transaction existing = transactionFor(TransactionType.DEBIT, TransactionStatus.SUCCEEDED,
                new BigDecimal("40.00"), new BigDecimal("100.00"), new BigDecimal("60.00"), null);

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("40.00"), "BRL");

        AuthorizationResult result = transactionService.authorize(command);

        assertThat(result.transaction()).isSameAs(existing);
        verify(transactionRecorder, never()).recordNew(any());
    }

    @Test
    void corridaDeIdempotenciaCapturaViolacaoDeChaveEBuscaTransacaoVencedora() {
        Account account = accountWithBalance(new BigDecimal("100.00"));
        Transaction winner = transactionFor(TransactionType.CREDIT, TransactionStatus.SUCCEEDED,
                new BigDecimal("50.00"), new BigDecimal("50.00"), new BigDecimal("100.00"), null);

        // Primeira checagem de idempotencia: ainda nao existe (a outra thread ainda nao commitou).
        // Segunda checagem (apos a excecao capturada): a transacao vencedora ja esta la.
        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(transactionRecorder.recordNew(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("50.00"), "BRL");

        AuthorizationResult result = transactionService.authorize(command);

        assertThat(result.transaction()).isSameAs(winner);
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
