package com.itau.transaction.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal amount) {
        super(String.format(
                "Saldo insuficiente na conta %s. Saldo atual: %s, valor do débito: %s",
                accountId, balance, amount
        ));
    }
}