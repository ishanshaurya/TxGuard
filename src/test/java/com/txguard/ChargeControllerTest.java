package com.txguard;

import com.txguard.internal.controller.ChargeController;
import com.txguard.internal.config.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link ChargeController}.
 *
 * <p>Uses {@code @WebMvcTest} so only the web layer is loaded — no DB, Redis,
 * or Rabbit connections needed. Fast enough to run on every commit.
 */
@WebMvcTest(ChargeController.class)
@Import(GlobalExceptionHandler.class)
class ChargeControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/charge with valid body → 200 OK with stub response")
    void validRequest_returns200() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "idempotency_key",      "test-key-001",
                "amount",               100,
                "currency",             "INR",
                "merchant_reference",   "order-xyz",
                "payment_method_token", "tok_visa_4242"
        ));

        mvc.perform(post("/api/v1/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idempotency_key", is("test-key-001")))
                .andExpect(jsonPath("$.charge_id",       is("STUB-0000")))
                .andExpect(jsonPath("$.status",          is("ACCEPTED")))
                .andExpect(jsonPath("$.processed_at",    notNullValue()));
    }

    // ── Validation failures ──────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("POST /api/v1/charge with invalid body → 400 Bad Request")
    void invalidRequest_returns400(String scenario, Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/v1/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error",  is("Validation failed")));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("missing idempotency_key", Map.of(
                        "amount", 100, "currency", "INR",
                        "merchant_reference", "ord-1", "payment_method_token", "tok_1"
                )),
                Arguments.of("amount = 0", Map.of(
                        "idempotency_key", "key-1", "amount", 0, "currency", "INR",
                        "merchant_reference", "ord-1", "payment_method_token", "tok_1"
                )),
                Arguments.of("currency lowercase", Map.of(
                        "idempotency_key", "key-1", "amount", 100, "currency", "inr",
                        "merchant_reference", "ord-1", "payment_method_token", "tok_1"
                )),
                Arguments.of("currency too long", Map.of(
                        "idempotency_key", "key-1", "amount", 100, "currency", "USDD",
                        "merchant_reference", "ord-1", "payment_method_token", "tok_1"
                )),
                Arguments.of("blank merchant_reference", Map.of(
                        "idempotency_key", "key-1", "amount", 100, "currency", "USD",
                        "merchant_reference", "", "payment_method_token", "tok_1"
                ))
        );
    }

    // ── Wrong content-type ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/charge with text/plain → 415 Unsupported Media Type")
    void wrongContentType_returns415() throws Exception {
        mvc.perform(post("/api/v1/charge")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
