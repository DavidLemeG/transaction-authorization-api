package com.itau.transaction.api.dto;

import com.itau.transaction.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(

        @NotNull(message = "accountId é obrigatório")
        UUID accountId,

        @NotNull(message = "type é obrigatório (CREDIT ou DEBIT)")
        TransactionType type,

        @NotNull(message = "amount é obrigatório")
        @Valid
        Amount amount
) {

    public record Amount(

            @NotNull(message = "value é obrigatório")
            @DecimalMin(value = "0.01", message = "value deve ser maior que zero")
            @Digits(integer = 17, fraction = 2, message = "value deve ter no máximo 2 casas decimais")
            BigDecimal value,

            @NotBlank(message = "currency é obrigatório")
            @Size(min = 3, max = 3, message = "currency deve seguir ISO4217 (3 letras)")
            String currency
    ) {
    }
}