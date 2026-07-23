package com.itau.transaction.repository;

import com.itau.transaction.domain.account.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Busca a conta aplicando lock pessimista (SELECT ... FOR UPDATE).
     * Garante que duas transações concorrentes na mesma conta sejam serializadas,
     * evitando race condition na leitura/escrita do saldo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}