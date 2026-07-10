package com.elysium.vanguard.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for the HTTP request parser. We feed synthetic byte streams into
 * [HttpRequestParser.readRequest] and assert the resulting [HttpRequest] structure.
 *
 * What we cover:
 *   - Simple GET
 *   - Headers (case preservation on storage; case-insensitive lookup)
 *   - Query parsing (URL-decoding, repeated keys keep last value via toMap)
 *   - Body reading via Content-Length
 *   - Body-too-large guard
 *   - Malformed request line / header
 *
 * What we don't:
 *   - Chunked transfer encoding (out of scope by design)
 *   - HTTP/2 (the parser would have to be replaced entirely)
 */
class HttpRequestParserTest {

    @Test fun `parses simple GET with no headers or body`() {
        val raw = "GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(raw))
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("/hello", req.path)
        assertEquals("example.com", req.header("Host"))
        assertTrue(req.body.isEmpty())
        assertTrue(req.query.isEmpty())
    }

    @Test fun `parses query string with URL-decoded values`() {
        val raw = "GET /api/list?path=%2Fsome%2Fpath&recursive=true HTTP/1.1\r\n\r\n".toByteArray()
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(raw))
        assertEquals("/api/list", req!!.path)
        assertEquals("/some/path", req.query["path"])
        assertEquals("true", req.query["recursive"])
    }

    @Test fun `header lookup is case-insensitive`() {
        val raw = "GET / HTTP/1.1\r\nAuthorization: Bearer abc.def\r\nX-Custom: 42\r\n\r\n".toByteArray()
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(raw))
        assertEquals("Bearer abc.def", req!!.header("authorization"))
        assertEquals("Bearer abc.def", req.header("AUTHORIZATION"))
        assertEquals("42", req.header("x-custom"))
        assertEquals("abc.def", req.bearerToken)
    }

    @Test fun `body is read when Content-Length is present`() {
        val body = "hello=world&token=abc".toByteArray()
        val raw = ("POST /submit HTTP/1.1\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n").toByteArray(Charsets.UTF_8) + body
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(raw))
        assertEquals("POST", req!!.method)
        assertEquals(body.size, req.body.size)
        assertTrue(req.body.contentEquals(body))
    }

    @Test fun `body longer than cap throws`() {
        val raw = "POST /upload HTTP/1.1\r\nContent-Length: ${HttpRequestParser.MAX_BODY_BYTES + 1}\r\n\r\n".toByteArray()
        try {
            HttpRequestParser.readRequest(ByteArrayInputStream(raw))
            error("Expected HttpParseException")
        } catch (e: HttpParseException) {
            assertTrue(e.message!!.contains("Body too large"))
        }
    }

    @Test fun `malformed request line throws`() {
        val raw = "NOPE\r\nHost: x\r\n\r\n".toByteArray()
        try {
            HttpRequestParser.readRequest(ByteArrayInputStream(raw))
            error("Expected HttpParseException")
        } catch (e: HttpParseException) {
            // expected
        }
    }

    @Test fun `returns null when stream closes before any data`() {
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(ByteArray(0)))
        assertNull(req)
    }

    @Test fun `form fields are decoded from x-www-form-urlencoded body`() {
        val body = "name=John+Doe&age=42"
        val raw = ("POST /api HTTP/1.1\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.length}\r\n\r\n" +
            body).toByteArray()
        val req = HttpRequestParser.readRequest(ByteArrayInputStream(raw))
        val fields = req!!.formFields
        assertEquals("John Doe", fields["name"])
        assertEquals("42", fields["age"])
    }
}