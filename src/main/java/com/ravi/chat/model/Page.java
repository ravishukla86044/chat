package com.ravi.chat.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results plus the cursor to fetch the next (older) page.
 * {@code nextCursor} is null when there are no more results.
 */
public record Page<T>(List<T> items, Long nextCursor) {

    public JsonObject toJson(String itemsKey, Function<T, JsonObject> mapper) {
        JsonArray arr = new JsonArray();
        items.forEach(item -> arr.add(mapper.apply(item)));
        return new JsonObject()
                .put(itemsKey, arr)
                .put("nextCursor", nextCursor);
    }
}
