package com.itau.transaction.domain.account;

import com.itau.transaction.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account newAccount() {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
    }

    @Test
    void novaContaNasceComSaldoZeroEMoedaBRL() {
        Account account = newAccount();

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getCurrency()).isEqualTo("BRL");
    }

    @Test
    void creditSomaCorretamenteAoSaldo() {
        Account account = newAccount();

        account.credit(new BigDecimal("100.00"));
        account.credit(new BigDecimal("50.50"));

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("150.50"));
    }

    @Test
    void debitSubtraiQuandoHaSaldoSuficiente() {
        Account account = newAccount();
        account.credit(new BigDecimal("100.00"));

        account.debit(new BigDecimal("30.00"));

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    void debitDoValorExatoDoSaldoDeveSerPermitidoEZerarSaldo() {
        Account account = newAccount();
        account.credit(new BigDecimal("100.00"));

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void debitUmCentavoAcimaDoSaldoLancaInsufficientFundsException() {
        Account account = newAccount();
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void canDebitRetornaTrueNaFronteiraDoSaldoExato() {
        Account account = newAccount();
        account.credit(new BigDecimal("100.00"));

        assertThat(account.canDebit(new BigDecimal("100.00"))).isTrue();
        assertThat(account.canDebit(new BigDecimal("100.01"))).isFalse();
    }

    @Test
    void canDebitRetornaFalseParaContaComSaldoZero() {
        Account account = newAccount();

        assertThat(account.canDebit(new BigDecimal("0.01"))).isFalse();
        assertThat(account.canDebit(BigDecimal.ZERO)).isTrue();
    }

    @Test
    void saldoNaoEAlteradoQuandoDebitoERecusado() {
        Account account = newAccount();
        account.credit(new BigDecimal("50.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("50.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void precisaoDecimalSomaZeroPontoUmMaisZeroPontoDoisIgualAZeroPontoTres() {
        Account account = newAccount();

        account.credit(new BigDecimal("0.1"));
        account.credit(new BigDecimal("0.2"));

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("0.3"));
    }
}
