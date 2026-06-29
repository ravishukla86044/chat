package com.ravi.chat.web;

import com.ravi.chat.service.MessagingService;
import io.vertx.ext.web.RoutingContext;

public class UserHandler {

    private final MessagingService service;

    public UserHandler(MessagingService service) {
        this.service = service;
    }

    /** POST /users {username} */
    public void create(RoutingContext ctx) {
        String username = RequestUtils.body(ctx).getString("username");
        service.createUser(username)
                .onSuccess(user -> RequestUtils.writeJson(ctx, 201, user.toJson()))
                .onFailure(ctx::fail);
    }
}
