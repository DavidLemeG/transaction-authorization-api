package com.itau.transaction.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.service.AuthorizationResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record TransactionResponse(
        TransactionData transaction,
        AccountData account
) {

    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransactionData(
            UUID id,
            TransactionType type,
            Amount amount,
            TransactionStatus status,
            String reason,
            OffsetDateTime timestamp
    ) {
    }

    public record AccountData(UUID id, Balance balance) {
    }

    public record Amount(BigDecimal value, String currency) {
    }

    public record Balance(BigDecimal amount, String currency) {
    }

    public static TransactionResponse from(AuthorizationResult result) {
        Transaction tx = result.transaction();
        Account acc = result.account();

        return new TransactionResponse(
                new TransactionData(
                        tx.getId(),
                        tx.getType(),
                        new Amount(tx.getAmount(), tx.getCurrency()),
                        tx.getStatus(),
                        tx.getReason(),
                        tx.getCreatedAt().atZoneSameInstant(BRAZIL).toOffsetDateTime()
                ),
                new AccountData(
                        acc.getId(),
                        // Usa o saldo gravado na transacao (nao o saldo "ao vivo" da conta):
                        // no reenvio idempotente, tx.getNewBalance() e o saldo de quando ESTA
                        // transacao foi processada, evitando misturar um resultado congelado
                        // (ex.: FAILED por saldo insuficiente) com o saldo atual da conta, que
                        // pode ja ter mudado por outras transacoes.
                        new Balance(tx.getNewBalance(), acc.getCurrency())
                )
        );
    }
}