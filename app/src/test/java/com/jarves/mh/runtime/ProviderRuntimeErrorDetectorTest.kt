package com.jarves.mh.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeErrorDetectorTest {
    @Test
    fun userNotFoundIsFatal() {
        assertEquals(
            "User not found. Check the API key and provider account.",
            ProviderRuntimeErrorDetector.detect("Failed to authenticate. API Error: 401 User not found."),
        )
    }

    @Test
    fun authenticationRetryIsFatalImmediately() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":401,"error":"authentication_failed"}"""
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("Claude Code connected"))
    }

    @Test
    fun toolResultContainingErrorKeywordsIsNotFatal() {
        val event = JSONObject()
            .put("type", "user")
            .put(
                "message",
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_1")
                                .put(
                                    "content",
                                    "- Better Auth rate limit with database storage\n" +
                                        "- the plugin kept failing with HTTP 401\n" +
                                        "- token expired, quota exceeded, invalid api key",
                                ),
                        ),
                    ),
            )
            .toString()
        assertNull(ProviderRuntimeErrorDetector.detect(event))
    }

    @Test
    fun assistantTextMentioningErrorKeywordsIsNotFatal() {
        val event = JSONObject()
            .put("type", "assistant")
            .put(
                "message",
                JSONObject().put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", "The plugin failed with HTTP 401 because of the rate limit."),
                    ),
                ),
            )
            .toString()
        assertNull(ProviderRuntimeErrorDetector.detect(event))
    }

    @Test
    fun assistantApiErrorMessageIsFatal() {
        val event = JSONObject()
            .put("type", "assistant")
            .put("error", "authentication_failed")
            .put(
                "message",
                JSONObject().put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", "Failed to authenticate. API Error: 401 Invalid API key"),
                    ),
                ),
            )
            .toString()
        assertEquals("The provider rejected the saved API key.", ProviderRuntimeErrorDetector.detect(event))
    }

    @Test
    fun errorResultIsFatal() {
        val event = """{"type":"result","subtype":"success","is_error":true,"result":"Failed to authenticate. API Error: 401 User not found."}"""
        assertEquals(
            "User not found. Check the API key and provider account.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun successfulResultMentioningErrorKeywordsIsNotFatal() {
        val event = """{"type":"result","subtype":"success","is_error":false,"result":"Fixed the HTTP 401 and rate limit handling."}"""
        assertNull(ProviderRuntimeErrorDetector.detect(event))
    }

    @Test
    fun rateLimitedRetryIsFatal() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":429,"error":"rate_limit"}"""
        assertEquals("The provider rejected the saved API key.", ProviderRuntimeErrorDetector.detect(event))
    }

    @Test
    fun transientServerRetryIsNotFatal() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":529,"error":"overloaded"}"""
        assertNull(ProviderRuntimeErrorDetector.detect(event))
    }
}
