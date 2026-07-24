package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRecorderTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionRecorder transactionRecorder;

    private UUID accountId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        transactionRecorder = new TransactionRecorder(accountRepository, transactionRepository);
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

    @Test
    void contaInexistenteLancaAccountNotFoundException() {
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.empty());

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("10.00"), "BRL");

        assertThatThrownBy(() -> transactionRecorder.recordNew(command))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void creditoResultaEmStatusSucceededESaldoAumentado() {
        Account account = accountWithBalance(new BigDecimal("50.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("25.00"), "BRL");

        AuthorizationResult result = transactionRecorder.recordNew(command);

        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    void debitoComSaldoResultaEmStatusSucceeded() {
        Account account = accountWithBalance(new BigDecimal("100.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("40.00"), "BRL");

        AuthorizationResult result = transactionRecorder.recordNew(command);

        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void debitoSemSaldoResultaEmFailedComSaldoInalteradoEReasonPreenchido() {
        Account account = accountWithBalance(new BigDecimal("30.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("30.01"), "BRL");

        AuthorizationResult result = transactionRecorder.recordNew(command);

        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.transaction().getReason()).isNotBlank();
        assertThat(result.account().getBalance()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void transacaoFailedTambemEPersistida() {
        Account account = accountWithBalance(new BigDecimal("10.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("999.00"), "BRL");

        transactionRecorder.recordNew(command);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void previousBalanceENewBalanceSaoGravadosCorretamente() {
        Account account = accountWithBalance(new BigDecimal("100.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.DEBIT, new BigDecimal("40.00"), "BRL");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        transactionRecorder.recordNew(command);
        verify(transactionRepository).save(captor.capture());

        assertThat(captor.getValue().getPreviousBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(captor.getValue().getNewBalance()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void transacaoConstruidaEIsNewParaForcarPersistEmVezDeMerge() {
        Account account = accountWithBalance(new BigDecimal("100.00"));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId, accountId, TransactionType.CREDIT, new BigDecimal("10.00"), "BRL");

        AuthorizationResult result = transactionRecorder.recordNew(command);

        assertThat(result.transaction().isNew())
                .as("Transaction recem-construida deve reportar isNew()==true, "
                        + "senao o Spring Data chama merge() em vez de persist() e duas "
                        + "requisicoes concorrentes com o mesmo id se sobrescrevem silenciosamente")
                .isTrue();
    }
}
