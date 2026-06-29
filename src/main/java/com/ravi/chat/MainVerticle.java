package com.ravi.chat;

import com.ravi.chat.config.AppConfig;
import com.ravi.chat.db.Migrations;
import com.ravi.chat.repo.ConversationRepository;
import com.ravi.chat.repo.MessageRepository;
import com.ravi.chat.repo.UserRepository;
import com.ravi.chat.service.MessagingService;
import com.ravi.chat.web.ApiRouter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    private Pool pool;

    @Override
    public void start(Promise<Void> startPromise) {
        AppConfig config = AppConfig.fromEnv();

        // Run schema migrations (blocking JDBC) before serving traffic.
        vertx.executeBlocking(() -> {
            Migrations.run(config);
            return null;
        }).compose(migrated -> {
            this.pool = buildPool(config);
            MessagingService service = new MessagingService(
                    new UserRepository(pool),
                    new ConversationRepository(pool),
                    new MessageRepository(pool));
            return vertx.createHttpServer()
                    .requestHandler(ApiRouter.create(vertx, service))
                    .listen(config.serverPort());
        }).onSuccess(server -> {
            log.info("Listening on port {}", server.actualPort());
            startPromise.complete();
        }).onFailure(err -> {
            log.error("Failed to start", err);
            startPromise.fail(err);
        });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (pool != null) {
            pool.close().onComplete(ar -> stopPromise.complete());
        } else {
            stopPromise.complete();
        }
    }

    private Pool buildPool(AppConfig config) {
        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setHost(config.dbHost())
                .setPort(config.dbPort())
                .setDatabase(config.dbName())
                .setUser(config.dbUser())
                .setPassword(config.dbPassword());
        PoolOptions poolOptions = new PoolOptions().setMaxSize(8);
        return MySQLBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }
}
