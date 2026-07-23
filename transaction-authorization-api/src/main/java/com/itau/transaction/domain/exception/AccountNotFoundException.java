package com.itau.transaction.domain.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super(String.format("Conta não encontrada: %s", accountId));
    }
}