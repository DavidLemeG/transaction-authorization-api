package com.itau.transaction.domain.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Implementa Persistable pois o id e atribuido pelo cliente (nao ha
 * @GeneratedValue nem @Version) -- sem isso, o Spring Data JPA considera
 * qualquer entidade com id nao-nulo como "nao nova" e chama merge() em vez de
 * persist() no save(), o que faz duas transacoes concorrentes com o MESMO
 * transactionId se sobrescreverem silenciosamente em vez de colidir na
 * constraint de chave primaria. Ver ADR 0014.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = false;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "previous_balance", nullable = false)
    private BigDecimal previousBalance;

    @Column(name = "new_balance", nullable = false)
    private BigDecimal newBalance;

    @Column(nullable = true, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Transaction(UUID id, UUID accountId, TransactionType type, BigDecimal amount,
                       String currency, TransactionStatus status, String reason,
                       BigDecimal previousBalance, BigDecimal newBalance) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.reason = reason;
        this.previousBalance = previousBalance;
        this.newBalance = newBalance;
        this.createdAt = OffsetDateTime.now();
        this.isNew = true;
    }
}