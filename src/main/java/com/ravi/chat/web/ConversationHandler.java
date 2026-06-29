package com.ravi.chat.web;

import com.ravi.chat.error.ApiException;
import com.ravi.chat.model.ConversationSummary;
import com.ravi.chat.model.Message;
import com.ravi.chat.service.MessagingService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.List;

public class ConversationHandler {

    private final MessagingService service;

    public ConversationHandler(MessagingService service) {
        this.service = service;
    }

    /** POST /conversations {name?, participantIds:[...]} — create a group. */
    public void create(RoutingContext ctx) {
        long callerId = RequestUtils.requireUserId(ctx);
        JsonObject body = RequestUtils.body(ctx);
        List<Long> participantIds = toLongList(body.getJsonArray("participantIds"));
        service.createConversation(callerId, body.getString("name"), participantIds)
                .onSuccess(conv -> RequestUtils.writeJson(ctx, 201, conv.toJson()))
                .onFailure(ctx::fail);
    }

    /** POST /conversations/{id}/messages {body} */
    public void sendMessage(RoutingContext ctx) {
        long callerId = RequestUtils.requireUserId(ctx);
        long conversationId = RequestUtils.pathLong(ctx, "id");
        JsonObject body = RequestUtils.body(ctx);
        service.sendToConversation(callerId, conversationId, body.getString("body"))
                .onSuccess(message -> RequestUtils.writeJson(ctx, 201, message.toJson()))
                .onFailure(ctx::fail);
    }

    /** GET /conversations/{id}/messages?limit=&before= */
    public void history(RoutingContext ctx) {
        long callerId = RequestUtils.requireUserId(ctx);
        long conversationId = RequestUtils.pathLong(ctx, "id");
        Integer limit = RequestUtils.queryInt(ctx, "limit");
        Long before = RequestUtils.queryLong(ctx, "before");
        service.getHistory(callerId, conversationId, limit, before)
                .onSuccess(page -> RequestUtils.writeJson(ctx, 200, page.toJson("messages", Message::toJson)))
                .onFailure(ctx::fail);
    }

    /** GET /conversations?limit=&before= */
    public void list(RoutingContext ctx) {
        long callerId = RequestUtils.requireUserId(ctx);
        Integer limit = RequestUtils.queryInt(ctx, "limit");
        Long before = RequestUtils.queryLong(ctx, "before");
        service.listConversations(callerId, limit, before)
                .onSuccess(page -> RequestUtils.writeJson(ctx, 200,
                        page.toJson("conversations", ConversationSummary::toJson)))
                .onFailure(ctx::fail);
    }

    private static List<Long> toLongList(JsonArray array) {
        List<Long> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (Object value : array) {
            if (value instanceof Number number) {
                result.add(number.longValue());
            } else {
                throw ApiException.badRequest("participantIds must be an array of numbers");
            }
        }
        return result;
    }
}
