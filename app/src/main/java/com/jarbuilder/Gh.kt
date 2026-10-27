package com.jarbuilder

import java.net.HttpURLConnection
import java.net.URL

class Gh(private val token: String) {
    private fun open(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "jar-builder-app")
        return c
    }

    fun call(method: String, url: String, body: String? = null): Pair<Int, String> {
        val c = open(method, url)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Download a release asset (works for private repos too). */
    fun download(assetApiUrl: String): ByteArray {
        val c = open("GET", assetApiUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in listOf(301, 302, 303, 307, 308)) {
            val loc = c.getHeaderField("Location") ?: throw RuntimeException("Redirect without location")
            val c2 = URL(loc).openConnection() as HttpURLConnection
            c2.connectTimeout = 30000
            c2.readTimeout = 120000
            if (c2.responseCode >= 400) throw RuntimeException("Download failed (${c2.responseCode})")
            return c2.inputStream.use { it.readBytes() }
        }
        if (code >= 400) throw RuntimeException("Download failed ($code)")
        return c.inputStream.use { it.readBytes() }
    }
}
