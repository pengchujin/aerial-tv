package app.orchard.tv.proxy

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fixed, credential-free diagnostics; never return a parser's original message. */
internal object SubscriptionFailures {
    fun code(error: Exception): String = when (error) {
        is UnknownHostException -> "subscription_dns_failed"
        is SocketTimeoutException -> "subscription_download_timeout"
        is SSLException -> "subscription_tls_failed"
        is ConnectException, is NoRouteToHostException -> "subscription_connection_failed"
        else -> error.message?.takeIf {
            it.matches(Regex("^(subscription_[a-z_]+|invalid_subscription_url|core_unavailable_for_device|core_request_failed)$"))
        } ?: if (error.javaClass.name.startsWith("org.snakeyaml.")) {
            "subscription_not_clash_yaml"
        } else "subscription_download_failed"
    }

    fun checkBody(text: String): String {
        val trimmed = text.trimStart()
        require(!trimmed.startsWith("<")) { "subscription_html_response" }
        // Generic URI/base64 subscriptions require the provider's Clash YAML endpoint.
        val uriList = Regex("^(ss|ssr|vmess|vless|trojan|hysteria2?|tuic)://", RegexOption.IGNORE_CASE)
        val compact = trimmed.filterNot(Char::isWhitespace)
        val encoded = compact.length >= 24 && compact.all { it.isLetterOrDigit() || it in "+/=_-" }
        require(!uriList.containsMatchIn(trimmed) && !encoded) { "subscription_requires_clash_format" }
        return text
    }
}
