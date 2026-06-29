package com.ravi.chat.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A conversation container. {@code type} is "direct" (1:1) or "group".
 * Membership lives in {@code conversation_participants}; participantIds is
 * populated when the caller has loaded it.
 */
public record Conversation(
        long id,
        String type,
        String name,
        LocalDateTime createdAt,
        List<Long> participantIds) {

    public static final String DIRECT = "direct";
    public static final String GROUP = "group";

    public boolean isDirect() {
        return DIRECT.equals(type);
    }

    public JsonObject toJson() {
        JsonArray participants = new JsonArray();
        if (participantIds != null) {
            participantIds.forEach(participants::add);
        }
        return new JsonObject()
                .put("id", id)
                .put("type", type)
                .put("name", name)
                .put("participantIds", participants)
                .put("createdAt", createdAt == null ? null : createdAt.toString());
    }
}
