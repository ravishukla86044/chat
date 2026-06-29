package com.ravi.chat.web;

import com.ravi.chat.error.ApiException;
import com.ravi.chat.service.MessagingService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class MessageHandler {

    private final MessagingService service;

    public MessageHandler(MessagingService service) {
        this.service = service;
    }

    /** POST /messages {recipientId, body} — DM convenience, sender = X-User-Id. */
    public void sendDirect(RoutingContext ctx) {
        long senderId = RequestUtils.requireUserId(ctx);
        JsonObject body = RequestUtils.body(ctx);
        Long recipientId = body.getLong("recipientId");
        if (recipientId == null) {
            throw ApiException.badRequest("recipientId is required");
        }
        service.sendDirectMessage(senderId, recipientId, body.getString("body"))
                .onSuccess(message -> RequestUtils.writeJson(ctx, 201, message.toJson()))
                .onFailure(ctx::fail);
    }
}
