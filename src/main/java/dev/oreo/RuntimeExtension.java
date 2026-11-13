package dev.oreo;

import com.sun.net.httpserver.HttpExchange;
import java.util.List;

/**
 * Installed OW capability; discovered through java.util.ServiceLoader.
 * Providers have one instance per interpreter and can intercept requests
 * before the matching OW route handler executes.
 */
public interface RuntimeExtension extends AutoCloseable {
    boolean supports(String operation);
    Object invoke(String operation, List<Object> arguments);
    default Response beforeRequest(HttpExchange exchange) { return null; }
    default void afterRequest(HttpExchange exchange, int status, long elapsedNanos) {}
    @Override default void close() throws Exception {}

    record Response(int status, String contentType, String body) {
        public Response {
            if (status < 100 || status > 599)
                throw new IllegalArgumentException("Invalid HTTP status");
            if (contentType == null || contentType.isBlank())
                contentType = "text/plain; charset=utf-8";
            if (body == null) body = "";
        }
    }
}
