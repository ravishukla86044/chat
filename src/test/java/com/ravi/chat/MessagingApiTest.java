package com.ravi.chat;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeded users: 1=alice, 2=bob, 3=carol. */
class MessagingApiTest extends IntegrationTestBase {

    private static final long ALICE = 1;
    private static final long BOB = 2;
    private static final long CAROL = 3;

    // --- Required: send, then fetch the history -------------------------

    @Test
    void sendDirectMessageThenFetchHistory() throws Exception {
        HttpResponse<Buffer> sent = sendDirect(ALICE, BOB, "hello bob");
        assertEquals(201, sent.statusCode());
        long conversationId = sent.bodyAsJsonObject().getLong("conversationId");

        HttpResponse<Buffer> history = get("/conversations/" + conversationId + "/messages", ALICE);
        assertEquals(200, history.statusCode());
        JsonArray messages = history.bodyAsJsonObject().getJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("hello bob", messages.getJsonObject(0).getString("body"));
        assertEquals(ALICE, messages.getJsonObject(0).getLong("senderId"));
    }

    // --- Required: pagination is stable as new messages arrive ----------

    @Test
    void paginationIsStableUnderConcurrentInsert() throws Exception {
        long conversationId = sendDirect(ALICE, BOB, "m1").bodyAsJsonObject().getLong("conversationId");
        for (int i = 2; i <= 5; i++) {
            sendDirect(ALICE, BOB, "m" + i);
        }

        // Page 1 (newest first): m5, m4
        JsonObject page1 = get("/conversations/" + conversationId + "/messages?limit=2", ALICE)
                .bodyAsJsonObject();
        assertEquals(List.of("m5", "m4"), bodies(page1));
        long cursor1 = page1.getLong("nextCursor");

        // A brand-new message arrives mid-pagination. It must NOT disturb older pages.
        sendDirect(ALICE, BOB, "m6");

        // Page 2 uses the cursor from page 1: m3, m2 — no m6, no repeat of m4/m5.
        JsonObject page2 = get("/conversations/" + conversationId + "/messages?limit=2&before=" + cursor1, ALICE)
                .bodyAsJsonObject();
        assertEquals(List.of("m3", "m2"), bodies(page2));
        long cursor2 = page2.getLong("nextCursor");

        // Page 3: m1, then no more.
        JsonObject page3 = get("/conversations/" + conversationId + "/messages?limit=2&before=" + cursor2, ALICE)
                .bodyAsJsonObject();
        assertEquals(List.of("m1"), bodies(page3));
        assertNull(page3.getLong("nextCursor"));

        // No duplicates and no skips across the paged sequence.
        List<String> all = new ArrayList<>();
        all.addAll(bodies(page1));
        all.addAll(bodies(page2));
        all.addAll(bodies(page3));
        assertEquals(List.of("m5", "m4", "m3", "m2", "m1"), all);
        Set<String> unique = new HashSet<>(all);
        assertEquals(all.size(), unique.size(), "no duplicate messages across pages");
        assertFalse(all.contains("m6"), "message that arrived mid-pagination must not appear in older pages");
    }

    // --- Required: a non-participant cannot read the conversation -------

    @Test
    void nonParticipantCannotReadConversation() throws Exception {
        long conversationId = sendDirect(ALICE, BOB, "private").bodyAsJsonObject().getLong("conversationId");

        HttpResponse<Buffer> denied = get("/conversations/" + conversationId + "/messages", CAROL);
        assertEquals(403, denied.statusCode());
        assertNotNull(denied.bodyAsJsonObject().getString("error"));

        // And the participant can still read it.
        assertEquals(200, get("/conversations/" + conversationId + "/messages", BOB).statusCode());
    }

    // --- Edge cases ------------------------------------------------------

    @Test
    void emptyBodyIsRejected() throws Exception {
        HttpResponse<Buffer> blank = sendDirect(ALICE, BOB, "   ");
        assertEquals(400, blank.statusCode());
    }

    @Test
    void unknownRecipientReturns404() throws Exception {
        HttpResponse<Buffer> resp = post("/messages", ALICE,
                new JsonObject().put("recipientId", 999).put("body", "hi"));
        assertEquals(404, resp.statusCode());
    }

    @Test
    void directConversationIsDeduplicated() throws Exception {
        long first = sendDirect(ALICE, BOB, "one").bodyAsJsonObject().getLong("conversationId");
        long second = sendDirect(BOB, ALICE, "two").bodyAsJsonObject().getLong("conversationId");
        assertEquals(first, second, "the same pair always maps to one direct conversation");
    }

    @Test
    void missingUserHeaderIsUnauthorized() throws Exception {
        HttpResponse<Buffer> resp = get("/conversations", null);
        assertEquals(401, resp.statusCode());
    }

    // --- Groups ----------------------------------------------------------

    @Test
    void groupConversationSupportsAllMembersAndDeniesOutsiders() throws Exception {
        HttpResponse<Buffer> created = post("/conversations", ALICE,
                new JsonObject().put("name", "team").put("participantIds", new JsonArray().add(BOB).add(CAROL)));
        assertEquals(201, created.statusCode());
        JsonObject conv = created.bodyAsJsonObject();
        long conversationId = conv.getLong("id");
        assertEquals("group", conv.getString("type"));
        assertTrue(conv.getJsonArray("participantIds").contains((int) ALICE));

        assertEquals(201, post("/conversations/" + conversationId + "/messages", BOB,
                new JsonObject().put("body", "hi team")).statusCode());

        // Every member can read it.
        assertEquals(200, get("/conversations/" + conversationId + "/messages", CAROL).statusCode());

        // A freshly created non-member is denied.
        long dave = post("/users", null, new JsonObject().put("username", "dave"))
                .bodyAsJsonObject().getLong("id");
        assertEquals(403, get("/conversations/" + conversationId + "/messages", dave).statusCode());
    }

    @Test
    void listConversationsReturnsUsersConversationsNewestFirst() throws Exception {
        long dm = sendDirect(ALICE, BOB, "hi").bodyAsJsonObject().getLong("conversationId");
        long group = post("/conversations", ALICE,
                new JsonObject().put("name", "team").put("participantIds", new JsonArray().add(CAROL)))
                .bodyAsJsonObject().getLong("id");
        // Make the group the most recent activity.
        post("/conversations/" + group + "/messages", ALICE, new JsonObject().put("body", "newest"));

        JsonObject body = get("/conversations", ALICE).bodyAsJsonObject();
        JsonArray conversations = body.getJsonArray("conversations");
        assertEquals(2, conversations.size());
        assertEquals(group, conversations.getJsonObject(0).getLong("conversationId"));
        assertEquals(dm, conversations.getJsonObject(1).getLong("conversationId"));
    }

    // --- More edge cases -------------------------------------------------

    @Test
    void cannotSendMessageToYourself() throws Exception {
        assertEquals(400, sendDirect(ALICE, ALICE, "talking to myself").statusCode());
    }

    @Test
    void missingRecipientIdIsRejected() throws Exception {
        assertEquals(400, post("/messages", ALICE, new JsonObject().put("body", "hi")).statusCode());
    }

    @Test
    void nonNumericUserHeaderIsUnauthorized() throws Exception {
        HttpResponse<Buffer> resp = await(
                client.get(port, "localhost", "/conversations").putHeader("X-User-Id", "abc").send());
        assertEquals(401, resp.statusCode());
    }

    @Test
    void nonNumericConversationIdIsBadRequest() throws Exception {
        assertEquals(400, get("/conversations/not-a-number/messages", ALICE).statusCode());
    }

    @Test
    void createGroupWithUnknownParticipantIsRejected() throws Exception {
        HttpResponse<Buffer> resp = post("/conversations", ALICE,
                new JsonObject().put("name", "ghosts").put("participantIds", new JsonArray().add(9999)));
        assertEquals(400, resp.statusCode());
    }

    @Test
    void createGroupWithoutOtherParticipantsIsRejected() throws Exception {
        HttpResponse<Buffer> resp = post("/conversations", ALICE,
                new JsonObject().put("name", "just me").put("participantIds", new JsonArray()));
        assertEquals(400, resp.statusCode());
    }

    @Test
    void groupParticipantsAreDeduplicated() throws Exception {
        JsonObject conv = post("/conversations", ALICE,
                new JsonObject().put("name", "dups")
                        .put("participantIds", new JsonArray().add(BOB).add(BOB).add(CAROL)))
                .bodyAsJsonObject();
        JsonArray participants = conv.getJsonArray("participantIds");
        assertEquals(3, participants.size());
        assertTrue(participants.contains((int) ALICE));
        assertTrue(participants.contains((int) BOB));
        assertTrue(participants.contains((int) CAROL));
    }

    @Test
    void limitIsCappedAtMaximum() throws Exception {
        long conversationId = sendDirect(ALICE, BOB, "m1").bodyAsJsonObject().getLong("conversationId");
        for (int i = 2; i <= 101; i++) {
            sendDirect(ALICE, BOB, "m" + i);
        }
        JsonObject page = get("/conversations/" + conversationId + "/messages?limit=1000", ALICE)
                .bodyAsJsonObject();
        assertEquals(100, page.getJsonArray("messages").size(), "limit is clamped to the max of 100");
        assertNotNull(page.getLong("nextCursor"));
    }

    @Test
    void cursorBeforeStartReturnsEmptyPage() throws Exception {
        long conversationId = sendDirect(ALICE, BOB, "only").bodyAsJsonObject().getLong("conversationId");
        JsonObject page = get("/conversations/" + conversationId + "/messages?before=1", ALICE)
                .bodyAsJsonObject();
        assertEquals(0, page.getJsonArray("messages").size());
        assertNull(page.getLong("nextCursor"));
    }

    // --- helpers ---------------------------------------------------------

    private HttpResponse<Buffer> sendDirect(long sender, long recipient, String body) throws Exception {
        return post("/messages", sender, new JsonObject().put("recipientId", recipient).put("body", body));
    }

    private static List<String> bodies(JsonObject page) {
        List<String> result = new ArrayList<>();
        for (Object item : page.getJsonArray("messages")) {
            result.add(((JsonObject) item).getString("body"));
        }
        return result;
    }
}
