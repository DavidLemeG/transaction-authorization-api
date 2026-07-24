package com.itau.transaction.service;

import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.messaging.dto.AccountCreatedEvent;
import com.itau.transaction.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
    }

    private AccountCreatedEvent eventFor(UUID accountId) {
        return new AccountCreatedEvent(new AccountCreatedEvent.AccountPayload(
                accountId, UUID.randomUUID(), String.valueOf(Instant.now().getEpochSecond()), AccountStatus.ENABLED));
    }

    @Test
    void criaContaComSaldoZeroQuandoNaoExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.existsById(accountId)).thenReturn(false);

        accountService.createIfAbsent(eventFor(accountId));

        ArgumentCaptor<com.itau.transaction.domain.account.Account> captor =
                ArgumentCaptor.forClass(com.itau.transaction.domain.account.Account.class);
        verify(accountRepository).save(captor.capture());

        assertThat(captor.getValue().getId()).isEqualTo(accountId);
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getCurrency()).isEqualTo("BRL");
    }

    @Test
    void naoRecriaContaQuandoJaExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.existsById(accountId)).thenReturn(true);

        accountService.createIfAbsent(eventFor(accountId));

        verify(accountRepository, never()).save(any());
    }
}
