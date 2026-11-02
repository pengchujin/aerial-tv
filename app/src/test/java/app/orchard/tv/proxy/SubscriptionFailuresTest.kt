package app.orchard.tv.proxy

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFailuresTest {
    @Test fun htmlLoginPageIsRejectedWithFixedCode() {
        val page = "\n  <!DOCTYPE html><html><head><title>Sign in</title></head>" +
            "<body><form action=\"$URL\"><input name=\"token\" value=\"$TOKEN\"></form></body></html>"
        val error = assertThrows(IllegalArgumentException::class.java) { SubscriptionFailures.checkBody(page) }
        assertEquals("subscription_html_response", error.message)
        assertEquals("subscription_html_response", SubscriptionFailures.code(error))
    }

    @Test fun lowercaseHtmlAndXmlBodiesAreRejected() {
        listOf("<html><body>login</body></html>", "<?xml version=\"1.0\"?><error/>", "\r\n\t<head></head>").forEach { body ->
            val error = assertThrows(IllegalArgumentException::class.java) { SubscriptionFailures.checkBody(body) }
            assertEquals("subscription_html_response", error.message)
        }
    }

    @Test fun clashYamlBodyIsAcceptedEvenWhenServedAsTextHtml() {
        // The real provider labels its Clash YAML as text/html; only the body decides, never the MIME type.
        assertEquals(CLASH_YAML, SubscriptionFailures.checkBody(CLASH_YAML))
        assertEquals(2, (MihomoConfig.parse(CLASH_YAML)["proxies"] as List<*>).size)
    }

    @Test fun angleBracketsInsideYamlAreNotMistakenForHtml() {
        val body = "proxies:\n  - {name: \"<HK> 01\", type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}\n"
        assertEquals(body, SubscriptionFailures.checkBody(body))
    }

    @Test fun uriListAndBase64SubscriptionsAskForClashFormat() {
        listOf(
            "ss://YWVzLTEyOC1nY206cHc@hk.example.invalid:443#HK\nvmess://eyJhZGQiOiJleGFtcGxlIn0=\n",
            "  TROJAN://pw@jp.example.invalid:443#JP\n",
            "c3M6Ly9ZV1Z6TFRFeU9DMW5ZMjA2Y0hjQGhrLmV4YW1wbGUuaW52YWxpZDo0NDMjSEsK\nZm9vYmFy",
        ).forEach { body ->
            val error = assertThrows(IllegalArgumentException::class.java) { SubscriptionFailures.checkBody(body) }
            assertEquals("subscription_requires_clash_format", error.message)
        }
    }

    @Test fun networkFailuresMapToFixedCodesWithoutSecrets() {
        mapOf<Exception, String>(
            UnknownHostException("sub.example.invalid") to "subscription_dns_failed",
            SocketTimeoutException("Read timed out for $URL") to "subscription_download_timeout",
            SSLHandshakeException("Certificate for $URL is not trusted") to "subscription_tls_failed",
            ConnectException("Failed to connect to $URL") to "subscription_connection_failed",
            NoRouteToHostException("No route to $URL") to "subscription_connection_failed",
            FileNotFoundException(URL) to "subscription_download_failed",
            IOException("unexpected end of stream on $URL") to "subscription_download_failed",
            IllegalStateException(URL) to "subscription_download_failed",
            RuntimeException() to "subscription_download_failed",
        ).forEach { (error, expected) ->
            val code = SubscriptionFailures.code(error)
            assertEquals(error.javaClass.name, expected, code)
            assertSafe(code)
        }
    }

    @Test fun fixedCodesPassThroughUnchanged() {
        listOf("subscription_http_unauthorized", "subscription_too_large", "invalid_subscription_url",
            "core_unavailable_for_device", "core_request_failed").forEach {
            assertEquals(it, SubscriptionFailures.code(IllegalArgumentException(it)))
        }
    }

    @Test fun codeWithAppendedDetailIsNotPassedThrough() {
        listOf("subscription_http_forbidden $URL", "subscription_html_response\n$URL", "invalid_subscription_url: $TOKEN",
            "Subscription_dns_failed").forEach { message ->
            val code = SubscriptionFailures.code(IllegalArgumentException(message))
            assertEquals(message, "subscription_download_failed", code)
            assertSafe(code)
        }
    }

    @Test fun yamlParserMessagesAreReplacedByFixedCode() {
        // SnakeYAML quotes the offending input, which may hold a provider token.
        val error = assertThrows(Exception::class.java) { MihomoConfig.parse("\"$TOKEN\": 1\n\"$TOKEN\": 2\n") }
        assertTrue(error.javaClass.name.startsWith("org.snakeyaml."))
        assertTrue("the raw parser message should contain the token", error.message.orEmpty().contains(TOKEN))
        val code = SubscriptionFailures.code(error)
        assertEquals("subscription_not_clash_yaml", code)
        assertSafe(code)
    }

    private fun assertSafe(code: String) {
        assertTrue(code, code.matches(Regex("[a-z_]+")))
        assertFalse(code.contains(TOKEN))
        assertFalse(code.contains("example.invalid"))
    }

    private companion object {
        const val TOKEN = "Tk9QRS1zZWNyZXQtdG9rZW4"
        const val URL = "https://sub.example.invalid/api/v1/client/subscribe?token=$TOKEN"
        val CLASH_YAML = """
            |# Served with Content-Type: text/html; charset=utf-8
            |mixed-port: 7890
            |proxies:
            |  - {name: "香港 01", type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |  - {name: "日本 02", type: trojan, server: jp.example.invalid, port: 443, password: pw, sni: jp.example.invalid}
            |proxy-groups:
            |  - {name: 节点选择, type: select, proxies: ["香港 01", "日本 02", DIRECT]}
            |rules:
            |  - MATCH,节点选择
            |""".trimMargin()
    }
}
