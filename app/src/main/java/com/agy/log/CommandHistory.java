package com.agy.log;

import org.json.JSONArray;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe history of recent command execution responses.
 */
public final class CommandHistory {

    public static final int DEFAULT_CAPACITY = 100;
    private final int capacity;
    private final ArrayDeque<CommandRecord> history;

    public CommandHistory() {
        this(DEFAULT_CAPACITY);
    }

    public CommandHistory(int capacity) {
        this.capacity = Math.max(10, capacity);
        this.history = new ArrayDeque<>(this.capacity);
    }

    public synchronized void record(CommandRecord record) {
        if (record == null) return;
        if (history.size() >= capacity) {
            history.removeFirst();
        }
        history.addLast(record);
    }

    public synchronized List<CommandRecord> getRecent(int maxCount) {
        int size = history.size();
        if (size == 0) {
            return new ArrayList<>(0);
        }
        int count = Math.min(Math.max(1, maxCount), size);
        int skip = size - count;
        List<CommandRecord> list = new ArrayList<>(count);
        int idx = 0;
        for (CommandRecord r : history) {
            if (idx++ >= skip) {
                list.add(r);
            }
        }
        return list;
    }

    public synchronized CommandRecord getById(String id) {
        if (id == null) return null;
        for (CommandRecord r : history) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    public static JSONArray toJsonArray(List<CommandRecord> records) {
        JSONArray arr = new JSONArray();
        if (records != null) {
            for (CommandRecord r : records) {
                arr.put(r.toJson());
            }
        }
        return arr;
    }
}
