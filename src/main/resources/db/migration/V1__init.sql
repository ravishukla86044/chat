-- Core schema for the messaging service.
-- Supports both 1:1 ("direct") and multi-party ("group") conversations.

CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- A conversation is a container for messages with a set of participants.
--   * type = 'direct' : exactly two participants, deduped to one per pair.
--   * type = 'group'  : any number of participants, optional name, never deduped.
--
-- dm_key gives race-safe dedup for direct conversations: it holds the canonical
-- "loId-hiId" string for direct conversations and NULL for groups. MySQL allows
-- many NULLs under a UNIQUE index, so groups are never blocked while a direct
-- pair can exist at most once.
CREATE TABLE conversations (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    type       VARCHAR(16)  NOT NULL,
    name       VARCHAR(128) NULL,
    dm_key     VARCHAR(40)  NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_conversations_dm_key (dm_key),
    CONSTRAINT ck_conversations_type CHECK (type IN ('direct', 'group'))
) ENGINE = InnoDB;

-- Membership / authorization. A user may read a conversation iff a row exists here.
CREATE TABLE conversation_participants (
    conversation_id BIGINT    NOT NULL,
    user_id         BIGINT    NOT NULL,
    joined_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_cp_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_cp_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- Lets us list "conversations this user is in" and join to recent activity.
CREATE INDEX ix_cp_user ON conversation_participants (user_id, conversation_id);

CREATE TABLE messages (
    id              BIGINT    NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT    NOT NULL,
    sender_id       BIGINT    NOT NULL,
    body            TEXT      NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_msg_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_msg_sender FOREIGN KEY (sender_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- Keyset pagination + "latest message per conversation" both read this index.
-- The auto-increment id is the monotonic ordering/cursor key.
CREATE INDEX ix_messages_conversation_id ON messages (conversation_id, id);
