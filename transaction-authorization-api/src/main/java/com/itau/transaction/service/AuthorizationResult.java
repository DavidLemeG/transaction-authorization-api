package com.itau.transaction.service;

import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.transaction.Transaction;

public record AuthorizationResult(Transaction transaction, Account account) {
}