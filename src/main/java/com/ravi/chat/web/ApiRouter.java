package com.ravi.chat.web;

import com.ravi.chat.error.ApiException;
import com.ravi.chat.service.MessagingService;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the HTTP routing table and the shared JSON failure handler. */
public final class ApiRouter {

    private static final Logger log = LoggerFactory.getLogger(ApiRouter.class);

    private ApiRouter() {
    }

    public static Router create(Vertx vertx, MessagingService service) {
        Router router = Router.router(vertx);
        router.route().handler(ApiRouter::logRequest);
        router.route().handler(BodyHandler.create());

        UserHandler users = new UserHandler(service);
        MessageHandler messages = new MessageHandler(service);
        ConversationHandler conversations = new ConversationHandler(service);

        router.get("/health").handler(ctx ->
                RequestUtils.writeJson(ctx, 200, new JsonObject().put("status", "ok")));

        router.post("/users").handler(users::create);

        router.post("/messages").handler(messages::sendDirect);

        router.post("/conversations").handler(conversations::create);
        router.get("/conversations").handler(conversations::list);
        router.post("/conversations/:id/messages").handler(conversations::sendMessage);
        router.get("/conversations/:id/messages").handler(conversations::history);

        router.route().failureHandler(ApiRouter::handleFailure);
        return router;
    }

    /** Logs one line per request once the response completes: method, uri, status, duration. */
    private static void logRequest(RoutingContext ctx) {
        long startNanos = System.nanoTime();
        ctx.addBodyEndHandler(v -> {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("{} {} -> {} ({} ms)",
                    ctx.request().method(), ctx.request().uri(),
                    ctx.response().getStatusCode(), millis);
        });
        ctx.next();
    }

    private static void handleFailure(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        int status;
        String message;
        if (failure instanceof ApiException api) {
            status = api.statusCode();
            message = api.getMessage();
        } else if (ctx.statusCode() == 404) {
            status = 404;
            message = "not found";
        } else {
            status = 500;
            message = "internal server error";
            log.error("Unhandled request failure", failure);
        }
        RequestUtils.writeJson(ctx, status, new JsonObject().put("error", message));
    }
}
