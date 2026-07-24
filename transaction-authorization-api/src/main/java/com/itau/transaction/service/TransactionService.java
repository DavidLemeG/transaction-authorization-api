package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public AuthorizationResult authorize(AuthorizationCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doAuthorize(command);
        } finally {
            sample.stop(meterRegistry.timer("transaction.authorization.latency"));
        }
    }

    private AuthorizationResult doAuthorize(AuthorizationCommand command) {

        // Idempotencia: transactionId e controlado pelo cliente (UUID na URL).
        // Se a mesma requisicao chegar duas vezes (retry de rede), retornamos a resposta
        // original sem reprocessar --- evitando duplo debito ou duplo credito.
        Optional<Transaction> existing = transactionRepository.findById(command.transactionId());
        if (existing.isPresent()) {
            log.info("Transacao {} ja processada. Retornando resposta idempotente.", command.transactionId());
            Transaction tx = existing.get();
            Account account = accountRepository.findById(tx.getAccountId())
                    .orElseThrow(() -> new AccountNotFoundException(tx.getAccountId()));
            return new AuthorizationResult(tx, account);
        }

        Account account = accountRepository.findByIdForUpdate(command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

        BigDecimal previousBalance = account.getBalance();
        OperationResult opResult = applyOperation(account, command);

        Transaction transaction = new Transaction(
                command.transactionId(),
                account.getId(),
                command.type(),
                command.amount(),
                command.currency(),
                opResult.status(),
                opResult.reason(),
                previousBalance,
                account.getBalance()
        );

        transactionRepository.save(transaction);

        meterRegistry.counter("transaction.processed",
                        "type", command.type().name(),
                        "status", opResult.status().name())
                .increment();

        log.info("Transacao {} {} para conta {}: {} (saldo {} -> {})",
                command.transactionId(), command.type(), account.getId(),
                opResult.status(), previousBalance, account.getBalance());

        return new AuthorizationResult(transaction, account);
    }

    private record OperationResult(TransactionStatus status, String reason) {}

    private OperationResult applyOperation(Account account, AuthorizationCommand command) {
        if (command.type() == TransactionType.CREDIT) {
            account.credit(command.amount());
            return new OperationResult(TransactionStatus.SUCCEEDED, null);
        }

        if (account.canDebit(command.amount())) {
            account.debit(command.amount());
            return new OperationResult(TransactionStatus.SUCCEEDED, null);
        }

        String reason = String.format(
                "Debito recusado: saldo insuficiente.");
        return new OperationResult(TransactionStatus.FAILED, reason);
    }

//    Melhoria conhecida: race condition entre dois retries simultaneos com o mesmo transactionId.
//    O check acima cobre o caso comum (retry sequencial). Para retries verdadeiramente simultaneos,
//    a segunda thread quebraria com DataIntegrityViolationException (PK duplicada).
//    Solucao: capturar a excecao e buscar a transacao salva pela outra thread.
//    Requer isolamento de transacao (@Transactional REQUIRES_NEW) para evitar rollback-only na transacao pai.
}