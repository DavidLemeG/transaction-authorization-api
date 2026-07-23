CREATE TABLE accounts (
                          id            UUID PRIMARY KEY,
                          owner_id      UUID NOT NULL,
                          balance       NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
                          currency      VARCHAR(3) NOT NULL DEFAULT 'BRL',
                          status        VARCHAR(20) NOT NULL,
                          created_at    TIMESTAMPTZ NOT NULL,
                          updated_at    TIMESTAMPTZ
);

CREATE TABLE transactions (
                              id                UUID PRIMARY KEY,
                              account_id        UUID NOT NULL REFERENCES accounts(id),
                              type              VARCHAR(10) NOT NULL,
                              amount            NUMERIC(19, 2) NOT NULL,
                              currency          VARCHAR(3) NOT NULL,
                              status            VARCHAR(20) NOT NULL,
                              previous_balance  NUMERIC(19, 2) NOT NULL,
                              new_balance       NUMERIC(19, 2) NOT NULL,
                              created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);