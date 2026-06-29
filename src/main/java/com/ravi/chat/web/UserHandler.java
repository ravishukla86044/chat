package com.ravi.chat.web;

import com.ravi.chat.service.MessagingService;
import io.vertx.ext.web.RoutingContext;

public class UserHandler {

    private final MessagingService service;

    public UserHandler(MessagingService service) {
        this.service = service;
    }

    /** POST /users {username, email} */
    public void create(RoutingContext ctx) {
        var body = RequestUtils.body(ctx);
        service.createUser(body.getString("username"), body.getString("email"))
                .onSuccess(user -> RequestUtils.writeJson(ctx, 201, user.toJson()))
                .onFailure(ctx::fail);
    }
}
