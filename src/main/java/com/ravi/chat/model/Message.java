package com.ravi.chat.model;

import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;

public record Message(
        long id,
        long conversationId,
        long senderId,
        String body,
        LocalDateTime createdAt) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("conversationId", conversationId)
                .put("senderId", senderId)
                .put("body", body)
                .put("createdAt", createdAt == null ? null : createdAt.toString());
    }
}
