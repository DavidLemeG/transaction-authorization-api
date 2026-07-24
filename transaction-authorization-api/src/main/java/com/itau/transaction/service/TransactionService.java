package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionRecorder transactionRecorder;
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
            return idempotentResult(existing.get());
        }

        try {
            AuthorizationResult result = transactionRecorder.recordNew(command);

            meterRegistry.counter("transaction.processed",
                            "type", command.type().name(),
                            "status", result.transaction().getStatus().name())
                    .increment();

            log.info("Transacao {} {} para conta {}: {} (saldo {} -> {})",
                    command.transactionId(), command.type(), result.account().getId(),
                    result.transaction().getStatus(), result.transaction().getPreviousBalance(),
                    result.transaction().getNewBalance());

            return result;

        } catch (DataIntegrityViolationException e) {
            // Duas requisicoes verdadeiramente concorrentes com o mesmo transactionId:
            // a outra ja venceu a corrida e inseriu a transacao primeiro (violacao real
            // de chave primaria, ver Transaction.isNew()/Persistable). Em vez de propagar
            // o erro, buscamos e devolvemos o resultado da transacao vencedora.
            log.info("Corrida de idempotencia detectada para {}; buscando resultado da transacao vencedora",
                    command.transactionId());

            Transaction winner = transactionRepository.findById(command.transactionId())
                    .orElseThrow(() -> e);
            return idempotentResult(winner);
        }
    }

    private AuthorizationResult idempotentResult(Transaction tx) {
        Account account = accountRepository.findById(tx.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(tx.getAccountId()));
        return new AuthorizationResult(tx, account);
    }
}
