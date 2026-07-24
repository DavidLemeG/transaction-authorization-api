package com.itau.transaction.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itau.transaction.domain.account.Account;
import com.itau.transaction.domain.account.AccountStatus;
import com.itau.transaction.domain.exception.AccountNotFoundException;
import com.itau.transaction.domain.transaction.Transaction;
import com.itau.transaction.domain.transaction.TransactionStatus;
import com.itau.transaction.domain.transaction.TransactionType;
import com.itau.transaction.service.AuthorizationResult;
import com.itau.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    private UUID accountId;
    private UUID transactionId;

    private AuthorizationResult resultFor(TransactionType type, TransactionStatus status,
                                           BigDecimal amount, BigDecimal balance, String reason) {
        Account account = new Account(accountId, UUID.randomUUID(), AccountStatus.ENABLED, OffsetDateTime.now());
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            account.credit(balance);
        }
        Transaction transaction = new Transaction(
                transactionId, accountId, type, amount, "BRL", status, reason,
                BigDecimal.ZERO, balance);
        return new AuthorizationResult(transaction, account);
    }

    private String requestBody(String accountId, String type, String value, String currency) {
        return """
                {
                  "accountId": "%s",
                  "type": %s,
                  "amount": { "value": %s, "currency": %s }
                }
                """.formatted(
                accountId,
                type == null ? "null" : "\"" + type + "\"",
                value,
                currency == null ? "null" : "\"" + currency + "\""
        );
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
    }

    @Test
    void payloadValidoRetorna200ComFormatoDoEnunciado() throws Exception {
        when(transactionService.authorize(any())).thenReturn(
                resultFor(TransactionType.CREDIT, TransactionStatus.SUCCEEDED,
                        new BigDecimal("97.07"), new BigDecimal("183.12"), null));

        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "CREDIT", "97.07", "BRL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.type").value("CREDIT"))
                .andExpect(jsonPath("$.transaction.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.transaction.amount.value").value(97.07))
                .andExpect(jsonPath("$.transaction.amount.currency").value("BRL"))
                .andExpect(jsonPath("$.account.balance.amount").value(183.12))
                .andExpect(jsonPath("$.account.balance.currency").value("BRL"));
    }

    @Test
    void accountIdAusenteRetorna400() throws Exception {
        String body = """
                {
                  "type": "CREDIT",
                  "amount": { "value": 10.00, "currency": "BRL" }
                }
                """;

        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void typeInvalidoRetorna400() throws Exception {
        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "TRANSFER", "10.00", "BRL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void amountZeroRetorna400() throws Exception {
        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "CREDIT", "0", "BRL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void amountNegativoRetorna400() throws Exception {
        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "CREDIT", "-10.00", "BRL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void currencyComDuasLetrasRetorna400() throws Exception {
        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "CREDIT", "10.00", "BR")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contaInexistenteRetorna404() throws Exception {
        when(transactionService.authorize(any())).thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "DEBIT", "10.00", "BRL")))
                .andExpect(status().isNotFound());
    }

    @Test
    void debitoSemSaldoRetorna200ComStatusFailedEReason() throws Exception {
        when(transactionService.authorize(any())).thenReturn(
                resultFor(TransactionType.DEBIT, TransactionStatus.FAILED,
                        new BigDecimal("500.00"), new BigDecimal("30.00"), "Debito recusado: saldo insuficiente."));

        mockMvc.perform(post("/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content(requestBody(accountId.toString(), "DEBIT", "500.00", "BRL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.status").value("FAILED"))
                .andExpect(jsonPath("$.transaction.reason").exists());
    }
}
