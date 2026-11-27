package org.json;

import java.util.ArrayList;
import java.util.List;

/** Minimal pure-Java org.json.JSONArray runtime shim for host JVM unit tests. */
public class JSONArray {

    private final List<Object> values = new ArrayList<>();

    public JSONArray() {
    }

    public JSONArray(String json) throws JSONException {
        JSONObject.Parser parser = new JSONObject.Parser(json);
        Object parsed = parser.parseValue();
        parser.expectEnd();
        if (!(parsed instanceof JSONArray)) {
            throw new JSONException("Value is not a JSONArray");
        }
        this.values.addAll(((JSONArray) parsed).values);
    }

    public int length() {
        return values.size();
    }

    public JSONArray put(Object value) {
        values.add(JSONObject.wrapValue(value));
        return this;
    }

    public JSONArray put(boolean value) {
        return put(Boolean.valueOf(value));
    }

    public JSONArray put(int value) {
        return put(Integer.valueOf(value));
    }

    public JSONArray put(long value) {
        return put(Long.valueOf(value));
    }

    public Object opt(int index) {
        return (index >= 0 && index < values.size()) ? values.get(index) : null;
    }

    public Object get(int index) throws JSONException {
        if (index < 0 || index >= values.size()) {
            throw new JSONException("Index out of bounds: " + index);
        }
        return values.get(index);
    }

    public String optString(int index) {
        return optString(index, "");
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        if (v == null || v == JSONObject.NULL) {
            return fallback;
        }
        return String.valueOf(v);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object v = get(index);
        if (!(v instanceof JSONObject)) {
            throw new JSONException("Element at " + index + " is not a JSONObject");
        }
        return (JSONObject) v;
    }

    public JSONArray optJSONArray(int index) {
        Object v = opt(index);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public JSONArray getJSONArray(int index) throws JSONException {
        Object v = get(index);
        if (!(v instanceof JSONArray)) {
            throw new JSONException("Element at " + index + " is not a JSONArray");
        }
        return (JSONArray) v;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, 0, 0);
        return sb.toString();
    }

    public String toString(int indentSpaces) throws JSONException {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, Math.max(0, indentSpaces), 0);
        return sb.toString();
    }

    void writeTo(StringBuilder sb, int indent, int depth) {
        if (values.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        int childDepth = depth + 1;
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (indent > 0) {
                sb.append('\n');
                JSONObject.appendIndent(sb, indent * childDepth);
            }
            JSONObject.writeValue(sb, values.get(i), indent, childDepth);
        }
        if (indent > 0) {
            sb.append('\n');
            JSONObject.appendIndent(sb, indent * depth);
        }
        sb.append(']');
    }
}
