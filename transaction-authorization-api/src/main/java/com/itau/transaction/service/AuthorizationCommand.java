package com.itau.transaction.service;

import com.itau.transaction.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public record AuthorizationCommand(
        UUID transactionId,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        String currency
) {
}