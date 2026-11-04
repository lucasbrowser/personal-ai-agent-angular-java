CREATE TABLE jarvis.conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES jarvis.users(id) ON DELETE CASCADE,
    title VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMPTZ,
    CONSTRAINT ck_conversations_status CHECK (status IN ('ACTIVE', 'COMPLETED')),
    CONSTRAINT uq_conversations_id_user_id UNIQUE (id, user_id)
);

CREATE INDEX idx_conversations_user_updated
    ON jarvis.conversations(user_id, updated_at DESC, id);

CREATE TABLE jarvis.conversation_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    source VARCHAR(40) NOT NULL,
    external_event_id VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conversation_messages_owner
        FOREIGN KEY (conversation_id, user_id)
        REFERENCES jarvis.conversations(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_conversation_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_conversation_messages_source
        CHECK (source IN ('REALTIME_USER_TRANSCRIPT', 'REALTIME_ASSISTANT_TRANSCRIPT')),
    CONSTRAINT ck_conversation_messages_role_source CHECK (
        (role = 'USER' AND source = 'REALTIME_USER_TRANSCRIPT') OR
        (role = 'ASSISTANT' AND source = 'REALTIME_ASSISTANT_TRANSCRIPT')
    )
);

CREATE INDEX idx_conversation_messages_timeline
    ON jarvis.conversation_messages(conversation_id, user_id, created_at, id);

CREATE UNIQUE INDEX uq_conversation_messages_external_event
    ON jarvis.conversation_messages(conversation_id, source, external_event_id)
    WHERE external_event_id IS NOT NULL;

CREATE TABLE jarvis.long_term_memories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES jarvis.users(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    confidence NUMERIC(3,2),
    source_type VARCHAR(30) NOT NULL,
    source_conversation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT ck_long_term_memories_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_long_term_memories_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_long_term_memories_source_type
        CHECK (source_type IN ('USER_PROVIDED', 'CONVERSATION_EXTRACTED')),
    CONSTRAINT fk_long_term_memories_source_conversation
        FOREIGN KEY (source_conversation_id, user_id)
        REFERENCES jarvis.conversations(id, user_id)
        ON DELETE SET NULL (source_conversation_id)
);

CREATE INDEX idx_long_term_memories_user_status_category
    ON jarvis.long_term_memories(user_id, status, category, updated_at DESC, id);

CREATE INDEX idx_long_term_memories_source_conversation
    ON jarvis.long_term_memories(source_conversation_id)
    WHERE source_conversation_id IS NOT NULL;
