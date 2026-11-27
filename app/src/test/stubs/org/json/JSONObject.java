package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal pure-Java org.json.JSONObject runtime shim for host JVM unit tests. */
public class JSONObject {

    public static final Object NULL = new Object() {
        @Override
        public boolean equals(Object obj) {
            return obj == this || obj == null;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "null";
        }
    };

    private final Map<String, Object> entries = new LinkedHashMap<>();

    public JSONObject() {
    }

    public JSONObject(String json) throws JSONException {
        Parser parser = new Parser(json);
        Object parsed = parser.parseValue();
        parser.expectEnd();
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Value is not a JSONObject");
        }
        this.entries.putAll(((JSONObject) parsed).entries);
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) {
            throw new JSONException("Null key");
        }
        if (value == null) {
            entries.remove(name);
        } else {
            entries.put(name, wrapValue(value));
        }
        return this;
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        return put(name, Boolean.valueOf(value));
    }

    public JSONObject put(String name, int value) throws JSONException {
        return put(name, Integer.valueOf(value));
    }

    public JSONObject put(String name, long value) throws JSONException {
        return put(name, Long.valueOf(value));
    }

    public JSONObject put(String name, double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new JSONException("Forbidden numeric value: " + value);
        }
        return put(name, Double.valueOf(value));
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    public boolean isNull(String name) {
        Object val = entries.get(name);
        return val == null || val == NULL;
    }

    public int length() {
        return entries.size();
    }

    public Iterator<String> keys() {
        return entries.keySet().iterator();
    }

    public Object opt(String name) {
        return name == null ? null : entries.get(name);
    }

    public Object get(String name) throws JSONException {
        if (name == null || !entries.containsKey(name)) {
            throw new JSONException("No value for " + name);
        }
        return entries.get(name);
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        Object v = opt(name);
        if (v == null || v == NULL) {
            return fallback;
        }
        return String.valueOf(v);
    }

    public boolean optBoolean(String name) {
        return optBoolean(name, false);
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = opt(name);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof String) {
            String s = (String) v;
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return fallback;
    }

    public int optInt(String name) {
        return optInt(name, 0);
    }

    public int optInt(String name, int fallback) {
        Object v = opt(name);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return (int) Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public long optLong(String name) {
        return optLong(name, 0L);
    }

    public long optLong(String name, long fallback) {
        Object v = opt(name);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public JSONObject optJSONObject(String name) {
        Object v = opt(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object v = get(name);
        if (!(v instanceof JSONObject)) {
            throw new JSONException("Value is not a JSONObject: " + name);
        }
        return (JSONObject) v;
    }

    public JSONArray optJSONArray(String name) {
        Object v = opt(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = get(name);
        if (!(v instanceof JSONArray)) {
            throw new JSONException("Value is not a JSONArray: " + name);
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
        if (entries.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        int childDepth = depth + 1;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (indent > 0) {
                sb.append('\n');
                appendIndent(sb, indent * childDepth);
            }
            sb.append(quote(e.getKey()));
            sb.append(indent > 0 ? ": " : ":");
            writeValue(sb, e.getValue(), indent, childDepth);
        }
        if (indent > 0) {
            sb.append('\n');
            appendIndent(sb, indent * depth);
        }
        sb.append('}');
    }

    public static String quote(String data) {
        if (data == null || data.isEmpty()) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(data.length() + 4);
        sb.append('"');
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static Object wrapValue(Object v) {
        if (v == null) {
            return NULL;
        }
        return v;
    }

    static void writeValue(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null || v == NULL) {
            sb.append("null");
        } else if (v instanceof JSONObject) {
            ((JSONObject) v).writeTo(sb, indent, depth);
        } else if (v instanceof JSONArray) {
            ((JSONArray) v).writeTo(sb, indent, depth);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else {
            sb.append(quote(String.valueOf(v)));
        }
    }

    static void appendIndent(StringBuilder sb, int count) {
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
    }

    static final class Parser {
        private final String input;
        private int pos = 0;

        Parser(String input) throws JSONException {
            if (input == null) {
                throw new JSONException("Null JSON input");
            }
            this.input = input;
        }

        void expectEnd() throws JSONException {
            skipWhitespace();
            if (pos != input.length()) {
                throw new JSONException("Trailing characters at index " + pos);
            }
        }

        Object parseValue() throws JSONException {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new JSONException("Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    consumeLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    consumeLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    consumeLiteral("null");
                    return NULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new JSONException("Unexpected char '" + c + "' at index " + pos);
            }
        }

        private JSONObject parseObject() throws JSONException {
            pos++; // '{'
            JSONObject obj = new JSONObject();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != '"') {
                    throw new JSONException("Expected object key at index " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != ':') {
                    throw new JSONException("Expected ':' at index " + pos);
                }
                pos++;
                Object val = parseValue();
                obj.entries.put(key, val);
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new JSONException("Unterminated object");
                }
                char sep = input.charAt(pos++);
                if (sep == '}') {
                    return obj;
                }
                if (sep != ',') {
                    throw new JSONException("Expected ',' or '}' at index " + (pos - 1));
                }
            }
        }

        private JSONArray parseArray() throws JSONException {
            pos++; // '['
            JSONArray arr = new JSONArray();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ']') {
                pos++;
                return arr;
            }
            while (true) {
                arr.put(parseValue());
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new JSONException("Unterminated array");
                }
                char sep = input.charAt(pos++);
                if (sep == ']') {
                    return arr;
                }
                if (sep != ',') {
                    throw new JSONException("Expected ',' or ']' at index " + (pos - 1));
                }
            }
        }

        private String parseString() throws JSONException {
            pos++; // opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= input.length()) {
                    throw new JSONException("Unterminated escape sequence");
                }
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(esc);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > input.length()) {
                            throw new JSONException("Invalid unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw new JSONException("Invalid unicode escape", e);
                        }
                        pos += 4;
                        break;
                    default:
                        throw new JSONException("Invalid escape '\\" + esc + "'");
                }
            }
            throw new JSONException("Unterminated string");
        }

        private Number parseNumber() throws JSONException {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            int intStart = pos;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == intStart) {
                throw new JSONException("Invalid number at index " + start);
            }
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                int fracStart = pos;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
                if (pos == fracStart) {
                    throw new JSONException("Invalid fraction at index " + start);
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                int expStart = pos;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
                if (pos == expStart) {
                    throw new JSONException("Invalid exponent at index " + start);
                }
            }
            String numStr = input.substring(start, pos);
            try {
                if (isFloat) {
                    return Double.valueOf(numStr);
                }
                long lv = Long.parseLong(numStr);
                if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) lv);
                }
                return Long.valueOf(lv);
            } catch (NumberFormatException e) {
                try {
                    return Double.valueOf(numStr);
                } catch (NumberFormatException e2) {
                    throw new JSONException("Invalid number: " + numStr, e2);
                }
            }
        }

        private void consumeLiteral(String lit) throws JSONException {
            if (!input.startsWith(lit, pos)) {
                throw new JSONException("Expected '" + lit + "' at index " + pos);
            }
            pos += lit.length();
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
