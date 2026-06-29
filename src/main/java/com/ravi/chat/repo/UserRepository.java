package com.ravi.chat.repo;

import com.ravi.chat.error.ApiException;
import com.ravi.chat.model.User;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.mysqlclient.MySQLException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Optional;

public class UserRepository {

    private static final int ER_DUP_ENTRY = 1062;

    private final Pool pool;

    public UserRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<User> create(String username, String email) {
        return pool.preparedQuery("INSERT INTO users (username, email) VALUES (?, ?)")
                .execute(Tuple.of(username, email))
                .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID))
                .flatMap(id -> findById(id).map(Optional::orElseThrow))
                .recover(err -> isDuplicateKey(err)
                        ? Future.failedFuture(ApiException.conflict("email already in use"))
                        : Future.failedFuture(err));
    }

    public Future<Optional<User>> findById(long id) {
        return pool.preparedQuery("SELECT id, username, email, created_at FROM users WHERE id = ?")
                .execute(Tuple.of(id))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(map(it.next())) : Optional.<User>empty();
                });
    }

    public Future<Boolean> existsById(long id) {
        return pool.preparedQuery("SELECT 1 FROM users WHERE id = ?")
                .execute(Tuple.of(id))
                .map(rs -> rs.size() > 0);
    }

    /** How many of the given ids exist as users. Used to validate membership sets. */
    public Future<Integer> countExisting(List<Long> ids) {
        if (ids.isEmpty()) {
            return Future.succeededFuture(0);
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS c FROM users WHERE id IN (");
        Tuple params = Tuple.tuple();
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.addLong(ids.get(i));
        }
        sql.append(")");
        return pool.preparedQuery(sql.toString()).execute(params)
                .map(rs -> rs.iterator().next().getInteger("c"));
    }

    private static User map(Row row) {
        return new User(
                row.getLong("id"),
                row.getString("username"),
                row.getString("email"),
                row.getLocalDateTime("created_at"));
    }

    private static boolean isDuplicateKey(Throwable err) {
        return err instanceof MySQLException mysql && mysql.getErrorCode() == ER_DUP_ENTRY;
    }
}
