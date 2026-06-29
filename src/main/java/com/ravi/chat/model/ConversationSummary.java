package com.ravi.chat.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A conversation as seen when listing a user's conversations: the conversation
 * itself, its participants, and a preview of the latest message (for ordering
 * and display).
 */
public record ConversationSummary(
        long id,
        String type,
        String name,
        List<Long> participantIds,
        Long lastMessageId,
        String lastMessageBody,
        Long lastMessageSenderId,
        LocalDateTime lastMessageAt) {

    public JsonObject toJson() {
        JsonArray participants = new JsonArray();
        if (participantIds != null) {
            participantIds.forEach(participants::add);
        }
        JsonObject json = new JsonObject()
                .put("conversationId", id)
                .put("type", type)
                .put("name", name)
                .put("participantIds", participants);
        if (lastMessageId != null) {
            json.put("lastMessage", new JsonObject()
                    .put("id", lastMessageId)
                    .put("body", lastMessageBody)
                    .put("senderId", lastMessageSenderId)
                    .put("createdAt", lastMessageAt == null ? null : lastMessageAt.toString()));
        } else {
            json.putNull("lastMessage");
        }
        return json;
    }
}
