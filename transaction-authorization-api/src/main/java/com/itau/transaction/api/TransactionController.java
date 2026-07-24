package com.itau.transaction.api;

import com.itau.transaction.api.dto.TransactionRequest;
import com.itau.transaction.api.dto.TransactionResponse;
import com.itau.transaction.service.AuthorizationCommand;
import com.itau.transaction.service.AuthorizationResult;
import com.itau.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Transações", description = "Autorização de crédito e débito em contas")
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(
            summary = "Autoriza uma transação de crédito ou débito",
            description = "Idempotente por transactionId: reenviar o mesmo id retorna a resposta original "
                    + "sem reprocessar. Débito que resultaria em saldo negativo é recusado "
                    + "(HTTP 200, status FAILED no corpo) em vez de gerar um erro HTTP."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transação processada (aprovada ou recusada)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TransactionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Payload inválido (campo ausente, tipo/moeda/valor fora do esperado)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> authorize(
            @Parameter(description = "Identificador da transação, gerado pelo cliente (UUID)", required = true)
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
