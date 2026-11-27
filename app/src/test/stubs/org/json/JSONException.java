package org.json;

/** Test-only runtime shim matching android.jar org.json.JSONException. */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }

    public JSONException(Throwable cause) {
        super(cause);
    }
}
