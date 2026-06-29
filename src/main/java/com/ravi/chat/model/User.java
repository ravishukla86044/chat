package com.ravi.chat.model;

import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;

public record User(long id, String username, String email, LocalDateTime createdAt) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("username", username)
                .put("email", email)
                .put("createdAt", createdAt == null ? null : createdAt.toString());
    }
}
