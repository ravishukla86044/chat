package com.ravi.chat.repo;

import com.ravi.chat.model.Message;
import com.ravi.chat.model.Page;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MessageRepository {

    private final Pool pool;

    public MessageRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Message> insert(long conversationId, long senderId, String body) {
        return pool.preparedQuery(
                        "INSERT INTO messages (conversation_id, sender_id, body) VALUES (?, ?, ?)")
                .execute(Tuple.of(conversationId, senderId, body))
                .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID))
                .flatMap(id -> findById(id).map(Optional::orElseThrow));
    }

    public Future<Optional<Message>> findById(long id) {
        return pool.preparedQuery(
                        "SELECT id, conversation_id, sender_id, body, created_at FROM messages WHERE id = ?")
                .execute(Tuple.of(id))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(map(it.next())) : Optional.<Message>empty();
                });
    }

    /**
     * Returns a page of a conversation's history, newest first, using keyset
     * pagination on the monotonic message id. {@code before} is the cursor: pass
     * null for the first (newest) page, then the returned nextCursor to go older.
     * Because new messages always get a higher id, already-fetched older pages
     * never gain or lose rows — no duplicates, no skips.
     */
    public Future<Page<Message>> page(long conversationId, int limit, Long before) {
        String sql;
        Tuple params;
        if (before == null) {
            sql = "SELECT id, conversation_id, sender_id, body, created_at FROM messages "
                    + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?";
            params = Tuple.of(conversationId, limit);
        } else {
            sql = "SELECT id, conversation_id, sender_id, body, created_at FROM messages "
                    + "WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?";
            params = Tuple.of(conversationId, before, limit);
        }
        return pool.preparedQuery(sql).execute(params).map(rs -> {
            List<Message> messages = new ArrayList<>();
            for (Row row : rs) {
                messages.add(map(row));
            }
            // A full page implies there may be more; the cursor is the oldest id returned.
            Long nextCursor = messages.size() == limit
                    ? messages.get(messages.size() - 1).id()
                    : null;
            return new Page<>(messages, nextCursor);
        });
    }

    private static Message map(Row row) {
        return new Message(
                row.getLong("id"),
                row.getLong("conversation_id"),
                row.getLong("sender_id"),
                row.getString("body"),
                row.getLocalDateTime("created_at"));
    }
}
