package com.ravi.chat.service;

import com.ravi.chat.error.ApiException;
import com.ravi.chat.model.Conversation;
import com.ravi.chat.model.ConversationSummary;
import com.ravi.chat.model.Message;
import com.ravi.chat.model.Page;
import com.ravi.chat.model.User;
import com.ravi.chat.repo.ConversationRepository;
import com.ravi.chat.repo.MessageRepository;
import com.ravi.chat.repo.UserRepository;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.List;

/**
 * Business rules: validation, find-or-create, and — most importantly —
 * authorization (a user may only read/post in conversations they belong to).
 */
public class MessagingService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public MessagingService(UserRepository users,
                            ConversationRepository conversations,
                            MessageRepository messages) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
    }

    public Future<User> createUser(String username) {
        if (isBlank(username)) {
            return Future.failedFuture(ApiException.badRequest("username is required"));
        }
        return users.create(username.trim());
    }

    /** DM convenience: find-or-create the direct conversation, then send. */
    public Future<Message> sendDirectMessage(long senderId, long recipientId, String body) {
        if (isBlank(body)) {
            return Future.failedFuture(ApiException.badRequest("body is required"));
        }
        if (senderId == recipientId) {
            return Future.failedFuture(ApiException.badRequest("cannot send a message to yourself"));
        }
        return requireUser(senderId, "sender")
                .compose(v -> requireUser(recipientId, "recipient"))
                .compose(v -> conversations.findOrCreateDirect(senderId, recipientId))
                .compose(conv -> messages.insert(conv.id(), senderId, body.trim()));
    }

    /** Creates a group conversation. The caller is always a member. */
    public Future<Conversation> createConversation(long callerId, String name, List<Long> participantIds) {
        List<Long> members = new ArrayList<>();
        members.add(callerId);
        if (participantIds != null) {
            members.addAll(participantIds);
        }
        List<Long> distinct = members.stream().distinct().toList();
        if (distinct.size() < 2) {
            return Future.failedFuture(
                    ApiException.badRequest("a conversation needs at least one other participant"));
        }
        return users.countExisting(distinct).compose(count -> {
            if (count != distinct.size()) {
                return Future.failedFuture(ApiException.badRequest("one or more participants do not exist"));
            }
            return conversations.createGroup(isBlank(name) ? null : name.trim(), distinct);
        });
    }

    /** Sends a message to an existing conversation; caller must be a participant. */
    public Future<Message> sendToConversation(long callerId, long conversationId, String body) {
        if (isBlank(body)) {
            return Future.failedFuture(ApiException.badRequest("body is required"));
        }
        return requireParticipant(conversationId, callerId)
                .compose(v -> messages.insert(conversationId, callerId, body.trim()));
    }

    /** Paginated history; caller must be a participant or it is denied. */
    public Future<Page<Message>> getHistory(long callerId, long conversationId, Integer limit, Long before) {
        return requireParticipant(conversationId, callerId)
                .compose(v -> messages.page(conversationId, clampLimit(limit), before));
    }

    public Future<Page<ConversationSummary>> listConversations(long callerId, Integer limit, Long before) {
        return conversations.listForUser(callerId, clampLimit(limit), before);
    }

    // --- helpers ---------------------------------------------------------

    private Future<Void> requireUser(long userId, String role) {
        return users.existsById(userId).compose(exists -> exists
                ? Future.succeededFuture()
                : Future.failedFuture(ApiException.notFound(role + " " + userId + " not found")));
    }

    /**
     * Enforces authorization. We deliberately return 404 when the conversation
     * doesn't exist, and 403 when it exists but the caller isn't a member —
     * the authorization-denied case the assignment calls out.
     */
    private Future<Void> requireParticipant(long conversationId, long userId) {
        return conversations.findById(conversationId).compose(opt -> {
            if (opt.isEmpty()) {
                return Future.failedFuture(ApiException.notFound("conversation " + conversationId + " not found"));
            }
            if (!opt.get().participantIds().contains(userId)) {
                return Future.failedFuture(
                        ApiException.forbidden("you are not a participant in this conversation"));
            }
            return Future.succeededFuture();
        });
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
