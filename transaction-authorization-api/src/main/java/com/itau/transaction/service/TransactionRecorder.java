package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.repository.AccountRepository;
import com.itau.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Isola a tentativa de gravar uma transacao NOVA numa transacao de banco
 * propria (REQUIRES_NEW). Isso e o que permite que, sob duas requisicoes
 * verdadeiramente concorrentes com o mesmo transactionId, apenas uma delas
 * tenha seu INSERT bem-sucedido -- a outra recebe uma violacao real de chave
 * primaria (ver Transaction.isNew()/Persistable) e tem SOMENTE esta transacao
 * revertida, sem contaminar a transacao do chamador (TransactionService).
 *
 * Precisa ser uma classe separada porque anotacoes @Transactional nao tem
 * efeito em chamadas internas (self-invocation) dentro da mesma classe --
 * o proxy do Spring so intercepta chamadas vindas de fora do bean.
 */
@Component
@RequiredArgsConstructor
class TransactionRecorder {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthorizationResult recordNew(AuthorizationCommand command) {
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

        // Transaction implementa Persistable e isNew()==true aqui, entao isto
        // e um persist() de verdade: uma segunda tentativa concorrente com o
        // mesmo id colide na constraint de chave primaria em vez de sobrescrever.
        transactionRepository.save(transaction);

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

        String reason = "Debito recusado: saldo insuficiente.";
        return new OperationResult(TransactionStatus.FAILED, reason);
    }
}
