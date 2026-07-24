package com.itau.transaction.api.dto;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.service.AuthorizationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionResponseTest {

    /**
     * Reproduz o cenario de reenvio idempotente de uma transacao FAILED depois
     * que a conta recebeu novos creditos: a resposta deve mostrar o saldo de
     * QUANDO a transacao foi processada (congelado em newBalance), nao o saldo
     * atual da conta -- senao um debito "recusado por saldo insuficiente"
     * aparece ao lado de um saldo que já cobriria a operação, o que confunde
     * quem le a resposta.
     */
    @Test
    void reenvioIdempotenteMostraSaldoDaEpocaDaTransacaoNaoSaldoAtualDaConta() {
        UUID accountId = UUID.randomUUID();

        Transaction failedTransaction = new Transaction(
                UUID.randomUUID(), accountId, TransactionType.DEBIT, new BigDecimal("10900.00"),
                "BRL", TransactionStatus.FAILED, "Debito recusado: saldo insuficiente.",
                new BigDecimal("10000.00"), new BigDecimal("10000.00"));

        Account accountComSaldoAtualMaiorAgora = new Account(
                accountId, UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
        accountComSaldoAtualMaiorAgora.credit(new BigDecimal("20000.00"));

        TransactionResponse response = TransactionResponse.from(
                new AuthorizationResult(failedTransaction, accountComSaldoAtualMaiorAgora));

        assertThat(response.transaction().status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(response.account().balance().amount()).isEqualByComparingTo("10000.00");
    }
}
