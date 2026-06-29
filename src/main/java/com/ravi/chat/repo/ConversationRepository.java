package com.ravi.chat.repo;

import com.ravi.chat.model.Conversation;
import com.ravi.chat.model.ConversationSummary;
import com.ravi.chat.model.Page;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.mysqlclient.MySQLException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConversationRepository {

    private static final int ER_DUP_ENTRY = 1062;

    private final Pool pool;

    public ConversationRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * Returns the single direct (1:1) conversation between the two users,
     * creating it if needed. Race-safe: the UNIQUE dm_key lets the database pick
     * one winner if two callers create the pair concurrently; the loser recovers
     * by reading the existing row.
     */
    public Future<Conversation> findOrCreateDirect(long userA, long userB) {
        long lo = Math.min(userA, userB);
        long hi = Math.max(userA, userB);
        String dmKey = lo + "-" + hi;

        return pool.withTransaction(conn -> conn
                .preparedQuery("INSERT INTO conversations (type, dm_key) VALUES ('direct', ?)")
                .execute(Tuple.of(dmKey))
                .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID))
                .flatMap(newId -> conn
                        .preparedQuery("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?), (?, ?)")
                        .execute(Tuple.of(newId, lo, newId, hi))
                        .map(ignored -> newId))
                .recover(err -> {
                    if (isDuplicateKey(err)) {
                        // Pair already exists — fetch its id.
                        return conn.preparedQuery("SELECT id FROM conversations WHERE dm_key = ?")
                                .execute(Tuple.of(dmKey))
                                .map(rs -> rs.iterator().next().getLong("id"));
                    }
                    return Future.failedFuture(err);
                })
                .flatMap(id -> loadById(conn, id).map(Optional::orElseThrow)));
    }

    /** Creates a group conversation with the given (deduplicated) members. */
    public Future<Conversation> createGroup(String name, List<Long> memberIds) {
        List<Long> members = memberIds.stream().distinct().toList();
        return pool.withTransaction(conn -> conn
                .preparedQuery("INSERT INTO conversations (type, name, dm_key) VALUES ('group', ?, NULL)")
                .execute(Tuple.of(name))
                .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID))
                .flatMap(newId -> {
                    StringBuilder sql = new StringBuilder(
                            "INSERT INTO conversation_participants (conversation_id, user_id) VALUES ");
                    Tuple params = Tuple.tuple();
                    for (int i = 0; i < members.size(); i++) {
                        sql.append(i == 0 ? "(?, ?)" : ", (?, ?)");
                        params.addLong(newId).addLong(members.get(i));
                    }
                    return conn.preparedQuery(sql.toString()).execute(params).map(ignored -> newId);
                })
                .flatMap(id -> loadById(conn, id).map(Optional::orElseThrow)));
    }

    public Future<Boolean> isParticipant(long conversationId, long userId) {
        return pool.preparedQuery(
                        "SELECT 1 FROM conversation_participants WHERE conversation_id = ? AND user_id = ?")
                .execute(Tuple.of(conversationId, userId))
                .map(rs -> rs.size() > 0);
    }

    public Future<Optional<Conversation>> findById(long conversationId) {
        return pool.withConnection(conn -> loadById(conn, conversationId));
    }

    private Future<Optional<Conversation>> loadById(SqlConnection conn, long conversationId) {
        return conn.preparedQuery("SELECT id, type, name, created_at FROM conversations WHERE id = ?")
                .execute(Tuple.of(conversationId))
                .flatMap(rs -> {
                    var it = rs.iterator();
                    if (!it.hasNext()) {
                        return Future.succeededFuture(Optional.empty());
                    }
                    Row row = it.next();
                    return conn.preparedQuery(
                                    "SELECT user_id FROM conversation_participants WHERE conversation_id = ? ORDER BY user_id")
                            .execute(Tuple.of(conversationId))
                            .map(prs -> {
                                List<Long> participants = new ArrayList<>();
                                prs.forEach(p -> participants.add(p.getLong("user_id")));
                                return Optional.of(new Conversation(
                                        row.getLong("id"),
                                        row.getString("type"),
                                        row.getString("name"),
                                        row.getLocalDateTime("created_at"),
                                        participants));
                            });
                });
    }

    /**
     * Lists the conversations a user belongs to, most-recent-activity first,
     * with a preview of the latest message. Keyset paginated on the latest
     * message id (conversations with no messages sort last).
     */
    public Future<Page<ConversationSummary>> listForUser(long userId, int limit, Long before) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.type, c.name,
                       lm.last_id, lm.last_body, lm.last_sender, lm.last_at
                FROM conversation_participants cp
                JOIN conversations c ON c.id = cp.conversation_id
                LEFT JOIN (
                    SELECT m.conversation_id,
                           m.id AS last_id, m.body AS last_body,
                           m.sender_id AS last_sender, m.created_at AS last_at
                    FROM messages m
                    JOIN (SELECT conversation_id, MAX(id) AS max_id
                          FROM messages GROUP BY conversation_id) mm
                      ON mm.conversation_id = m.conversation_id AND mm.max_id = m.id
                ) lm ON lm.conversation_id = c.id
                WHERE cp.user_id = ?
                """);
        Tuple params = Tuple.of(userId);
        if (before != null) {
            sql.append(" AND lm.last_id < ?");
            params.addLong(before);
        }
        sql.append(" ORDER BY (lm.last_id IS NULL), lm.last_id DESC LIMIT ?");
        params.addInteger(limit);

        return pool.preparedQuery(sql.toString()).execute(params).flatMap(rs -> {
            List<ConversationSummary> summaries = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            for (Row row : rs) {
                summaries.add(new ConversationSummary(
                        row.getLong("id"),
                        row.getString("type"),
                        row.getString("name"),
                        new ArrayList<>(),
                        row.getLong("last_id"),
                        row.getString("last_body"),
                        row.getLong("last_sender"),
                        row.getLocalDateTime("last_at")));
                ids.add(row.getLong("id"));
            }
            if (ids.isEmpty()) {
                return Future.succeededFuture(new Page<>(summaries, null));
            }
            return loadParticipants(ids).map(byConversation -> {
                List<ConversationSummary> enriched = new ArrayList<>();
                for (ConversationSummary s : summaries) {
                    enriched.add(new ConversationSummary(
                            s.id(), s.type(), s.name(),
                            byConversation.getOrDefault(s.id(), List.of()),
                            s.lastMessageId(), s.lastMessageBody(),
                            s.lastMessageSenderId(), s.lastMessageAt()));
                }
                ConversationSummary last = enriched.get(enriched.size() - 1);
                Long nextCursor = (enriched.size() == limit && last.lastMessageId() != null)
                        ? last.lastMessageId() : null;
                return new Page<>(enriched, nextCursor);
            });
        });
    }

    private Future<Map<Long, List<Long>>> loadParticipants(List<Long> conversationIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT conversation_id, user_id FROM conversation_participants WHERE conversation_id IN (");
        Tuple params = Tuple.tuple();
        for (int i = 0; i < conversationIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.addLong(conversationIds.get(i));
        }
        sql.append(") ORDER BY user_id");
        return pool.preparedQuery(sql.toString()).execute(params).map((RowSet<Row> rs) -> {
            Map<Long, List<Long>> byConversation = new LinkedHashMap<>();
            for (Row row : rs) {
                byConversation
                        .computeIfAbsent(row.getLong("conversation_id"), k -> new ArrayList<>())
                        .add(row.getLong("user_id"));
            }
            return byConversation;
        });
    }

    private static boolean isDuplicateKey(Throwable err) {
        return err instanceof MySQLException mysql && mysql.getErrorCode() == ER_DUP_ENTRY;
    }
}
