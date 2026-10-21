package com.eye3y3.finsync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig.Companion.INFINITE_TIMEOUT_MS

/** The HTTP client the app uses: long downloads are fine, a dead connection is noticed after a minute. */
fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = INFINITE_TIMEOUT_MS
    }
}
