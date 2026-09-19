package com.seatsurge.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.seatsurge.common.exception.ApiException;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;

import tools.jackson.databind.ObjectMapper;

/**
 * Makes a POST safe to retry. The first request with a given Idempotency-Key runs and its response is
 * stored; later requests with the same key get the stored response back instead of running again.
 *
 * <ul>
 *   <li>same key, different request -> 422 (the key was reused by mistake)</li>
 *   <li>same key while the first request is still running -> 409</li>
 *   <li>the first request failed -> the key is freed so the client can retry</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<?> execute(Long userId, String key, String requestFingerprint, HttpStatus successStatus,
            Supplier<?> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "Header " + HEADER + " (1-100 characters) is required for this request");
        }
        String requestHash = sha256(requestFingerprint);

        int claimed = jdbc.update("""
                insert into idempotency_keys (user_id, idem_key, request_hash) values (?, ?, ?)
                on conflict (user_id, idem_key) do nothing
                """, userId, key, requestHash);
        if (claimed == 0) {
            return replay(userId, key, requestHash);
        }

        try {
            Object result = action.get();
            jdbc.update("update idempotency_keys set response_status = ?, response_body = ? where user_id = ? and idem_key = ?",
                    successStatus.value(), objectMapper.writeValueAsString(result), userId, key);
            return ResponseEntity.status(successStatus).body(result);
        } catch (RuntimeException e) {
            jdbc.update("delete from idempotency_keys where user_id = ? and idem_key = ?", userId, key);
            throw e;
        }
    }

    private ResponseEntity<?> replay(Long userId, String key, String requestHash) {
        List<StoredResponse> rows = jdbc.query(
                "select request_hash, response_status, response_body from idempotency_keys where user_id = ? and idem_key = ?",
                (rs, i) -> new StoredResponse(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)),
                userId, key);
        if (rows.isEmpty()) {
            // The original request failed and freed the key between our insert and this read.
            throw new ConflictException("IDEMPOTENT_REQUEST_IN_PROGRESS", "Please retry the request");
        }
        StoredResponse stored = rows.getFirst();
        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
        if (stored.status() == null) {
            throw new ConflictException("IDEMPOTENT_REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed");
        }
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, "true")
                .body(stored.body());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record StoredResponse(String requestHash, Integer status, String body) {
    }
}
