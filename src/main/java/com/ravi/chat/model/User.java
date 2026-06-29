package com.ravi.chat.model;

import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;

public record User(long id, String username, LocalDateTime createdAt) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("username", username)
                .put("createdAt", createdAt == null ? null : createdAt.toString());
    }
}
