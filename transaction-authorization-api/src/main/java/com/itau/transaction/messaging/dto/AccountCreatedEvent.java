package com.itau.transaction.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itau.transaction.domain.account.AccountStatus;

import java.util.UUID;

public record AccountCreatedEvent(AccountPayload account) {

    public record AccountPayload(
            UUID id,
            UUID owner,
            @JsonProperty("created_at") String createdAt,
            AccountStatus status
    ) {
    }
}