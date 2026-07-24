package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.messaging.dto.AccountCreatedEvent;
import com.itau.transaction.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public void createIfAbsent(AccountCreatedEvent event) {
        AccountCreatedEvent.AccountPayload payload = event.account();

        if (accountRepository.existsById(payload.id())) {
            log.trace("Conta {} já existe, ignorando evento duplicado", payload.id());
            return;
        }

        Account account = new Account(
                payload.id(),
                payload.owner(),
                payload.status(),
                toOffsetDateTime(payload.createdAt())
        );

        accountRepository.save(account);
        log.trace("Conta {} criada com saldo zero", payload.id());
    }

    private OffsetDateTime toOffsetDateTime(String epochSeconds) {
        return Instant.ofEpochSecond(Long.parseLong(epochSeconds)).atOffset(ZoneOffset.UTC);
    }
}