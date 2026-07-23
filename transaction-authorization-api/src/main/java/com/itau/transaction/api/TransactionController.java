package com.itau.transaction.api;

import com.itau.transaction.api.dto.TransactionRequest;
import com.itau.transaction.api.dto.TransactionResponse;
import com.itau.transaction.service.AuthorizationCommand;
import com.itau.transaction.service.AuthorizationResult;
import com.itau.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> authorize(
            @PathVariable UUID transactionId,
            @Valid @RequestBody TransactionRequest request) {

        AuthorizationCommand command = new AuthorizationCommand(
                transactionId,
                request.accountId(),
                request.type(),
                request.amount().value(),
                request.amount().currency()
        );

        AuthorizationResult result = transactionService.authorize(command);

        return ResponseEntity.ok(TransactionResponse.from(result));
    }
}