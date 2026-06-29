package com.ravi.chat.web;

import com.ravi.chat.error.ApiException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/** Small helpers for reading request inputs and writing JSON responses. */
final class RequestUtils {

    static final String USER_HEADER = "X-User-Id";

    private RequestUtils() {
    }

    /** Caller identity (stand-in for real auth). Required on protected routes. */
    static long requireUserId(RoutingContext ctx) {
        String raw = ctx.request().getHeader(USER_HEADER);
        if (raw == null || raw.isBlank()) {
            throw ApiException.unauthorized(USER_HEADER + " header is required");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.unauthorized(USER_HEADER + " must be a numeric user id");
        }
    }

    static long pathLong(RoutingContext ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    /** Optional numeric query param; returns null when absent, 400 when malformed. */
    static Long queryLong(RoutingContext ctx, String name) {
        String raw = ctx.request().getParam(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    static Integer queryInt(RoutingContext ctx, String name) {
        Long value = queryLong(ctx, name);
        return value == null ? null : value.intValue();
    }

    static JsonObject body(RoutingContext ctx) {
        JsonObject json = ctx.body() == null ? null : ctx.body().asJsonObject();
        if (json == null) {
            throw ApiException.badRequest("a JSON request body is required");
        }
        return json;
    }

    static void writeJson(RoutingContext ctx, int status, JsonObject payload) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(payload.encode());
    }
}
