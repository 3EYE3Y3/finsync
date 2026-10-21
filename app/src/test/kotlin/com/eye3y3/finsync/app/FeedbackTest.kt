package com.eye3y3.finsync.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackTest {
    @Test fun onlyVersionChannelAndroidAndModel() = assertEquals(
        "https://example.org/feedback?version=0.1.0%20%286%29&channel=github&android=15&device=Google%20Pixel%208",
        feedbackUrl("https://example.org/feedback", "0.1.0 (6)", "github", "15", "Google Pixel 8"),
    )
}
