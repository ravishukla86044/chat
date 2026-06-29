package com.ravi.chat;

import com.ravi.chat.config.AppConfig;
import com.ravi.chat.db.Migrations;
import com.ravi.chat.repo.ConversationRepository;
import com.ravi.chat.repo.MessageRepository;
import com.ravi.chat.repo.UserRepository;
import com.ravi.chat.service.MessagingService;
import com.ravi.chat.web.ApiRouter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.MySQLContainer;

import java.util.concurrent.TimeUnit;

/**
 * Boots a real MySQL (Testcontainers), runs the Flyway migrations, and starts
 * the actual Vert.x HTTP server so tests exercise the API end-to-end over HTTP.
 * Seeded users from V2 are: 1=alice, 2=bob, 3=carol.
 */
abstract class IntegrationTestBase {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chatpass");

    protected static Vertx vertx;
    protected static Pool pool;
    private static HttpServer server;
    protected static WebClient client;
    protected static int port;

    @BeforeAll
    static void startAll() throws Exception {
        MYSQL.start();

        AppConfig config = new AppConfig(
                MYSQL.getHost(),
                MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT),
                "chat", "chat", "chatpass", 0);
        Migrations.run(config);

        vertx = Vertx.vertx();
        pool = buildPool(config);
        MessagingService service = new MessagingService(
                new UserRepository(pool),
                new ConversationRepository(pool),
                new MessageRepository(pool));

        server = await(vertx.createHttpServer()
                .requestHandler(ApiRouter.create(vertx, service))
                .listen(0));
        port = server.actualPort();
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void stopAll() throws Exception {
        if (client != null) client.close();
        if (server != null) await(server.close());
        if (pool != null) await(pool.close());
        if (vertx != null) await(vertx.close());
        MYSQL.stop();
    }

    /** Each test starts from a clean slate (users are kept; conversations/messages cleared). */
    @BeforeEach
    void clean() throws Exception {
        await(pool.query("DELETE FROM messages").execute());
        await(pool.query("DELETE FROM conversation_participants").execute());
        await(pool.query("DELETE FROM conversations").execute());
    }

    // --- HTTP helpers ----------------------------------------------------

    protected HttpResponse<Buffer> post(String path, Long userId, JsonObject body) throws Exception {
        var request = client.post(port, "localhost", path);
        if (userId != null) {
            request.putHeader("X-User-Id", String.valueOf(userId));
        }
        return await(request.sendJsonObject(body));
    }

    protected HttpResponse<Buffer> get(String path, Long userId) throws Exception {
        var request = client.get(port, "localhost", path);
        if (userId != null) {
            request.putHeader("X-User-Id", String.valueOf(userId));
        }
        return await(request.send());
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Pool buildPool(AppConfig config) {
        MySQLConnectOptions options = new MySQLConnectOptions()
                .setHost(config.dbHost())
                .setPort(config.dbPort())
                .setDatabase(config.dbName())
                .setUser(config.dbUser())
                .setPassword(config.dbPassword());
        return MySQLBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(options)
                .using(vertx)
                .build();
    }
}
