package com.txguard.internal.service;

import com.txguard.internal.exception.IdempotencyConflictException;
import com.txguard.internal.model.ChargeRequest;
import com.txguard.internal.model.ChargeResponse;
import com.txguard.internal.model.IdempotencyRecord;
import com.txguard.internal.model.IdempotencyStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    IdempotencyService service;
    ObjectMapper objectMapper;

    ChargeRequest baseRequest = new ChargeRequest(
            "key-001", new BigDecimal("5000"), "INR", "order-001", "tok_visa"
    );

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new IdempotencyService(redisTemplate, objectMapper, 24);
    }

    @Test
    @DisplayName("New key → returns empty Optional (proceed with processing)")
    void newKey_returnsEmpty() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        Optional<ChargeResponse> result = service.checkAndClaim(baseRequest);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Duplicate key + same body + COMPLETED → returns cached response")
    void duplicateKey_sameBody_completed_returnsCached() throws Exception {
        ChargeResponse cachedResponse = new ChargeResponse(
                "key-001", "STUB-0000", "ACCEPTED", "cached", Instant.now()
        );
        // Compute the real fingerprint to match what service produces
        IdempotencyRecord completedRecord = IdempotencyRecord.completed(
                computeFingerprint(baseRequest), cachedResponse
        );

        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(completedRecord));

        Optional<ChargeResponse> result = service.checkAndClaim(baseRequest);

        assertThat(result).isPresent();
        assertThat(result.get().chargeId()).isEqualTo("STUB-0000");
    }

    @Test
    @DisplayName("Duplicate key + same body + PROCESSING → throws ALREADY_PROCESSING")
    void duplicateKey_sameBody_processing_throwsConflict() throws Exception {
        IdempotencyRecord processingRecord = IdempotencyRecord.processing(
                computeFingerprint(baseRequest)
        );

        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(processingRecord));

        assertThatThrownBy(() -> service.checkAndClaim(baseRequest))
                .isInstanceOf(IdempotencyConflictException.class)
                .satisfies(ex -> assertThat(
                        ((IdempotencyConflictException) ex).getReason()
                ).isEqualTo(IdempotencyConflictException.Reason.ALREADY_PROCESSING));
    }

    @Test
    @DisplayName("Duplicate key + DIFFERENT body → throws BODY_MISMATCH")
    void duplicateKey_differentBody_throwsMismatch() throws Exception {
        // Store record with a fingerprint for a different amount
        IdempotencyRecord differentRecord = IdempotencyRecord.processing("different-fingerprint-abc123");

        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(differentRecord));

        assertThatThrownBy(() -> service.checkAndClaim(baseRequest))
                .isInstanceOf(IdempotencyConflictException.class)
                .satisfies(ex -> assertThat(
                        ((IdempotencyConflictException) ex).getReason()
                ).isEqualTo(IdempotencyConflictException.Reason.BODY_MISMATCH));
    }

    @Test
    @DisplayName("complete() writes COMPLETED record to Redis")
    void complete_writesCompletedRecord() {
        ChargeResponse response = ChargeResponse.stubFor(baseRequest);

        service.complete(baseRequest, response);

        verify(valueOps).set(
                eq("idempotency:charge:key-001"),
                argThat(json -> json.contains("COMPLETED")),
                any()
        );
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private String computeFingerprint(ChargeRequest req) throws Exception {
        String canonical = String.join("|",
                req.amount().toPlainString(),
                req.currency(),
                req.merchantReference(),
                req.paymentMethodToken()
        );
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }
}
