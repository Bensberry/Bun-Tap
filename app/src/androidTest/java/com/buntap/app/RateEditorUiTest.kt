package com.buntap.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.TapRate
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.theme.BunTapTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The TPS ⇄ milliseconds editor keeps both fields in sync and rejects invalid input. */
@RunWith(AndroidJUnit4::class)
class RateEditorUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var rate by mutableStateOf(TapRate.fromTps(5.0))
    private var changes = 0

    private fun setUp() {
        compose.setContent {
            BunTapTheme {
                RateEditor(rate = rate, limits = RateLimits(maxTps = 20.0), onRateChange = {
                    rate = it
                    changes++
                })
            }
        }
    }

    @Test
    fun typingTpsUpdatesInterval() {
        setUp()
        compose.onNodeWithTag("rate_tps").performTextReplacement("10")
        compose.onNodeWithTag("rate_ms").assertTextContains("100")
        assertEquals(100_000L, rate.intervalMicros)
    }

    @Test
    fun typingIntervalUpdatesTps() {
        setUp()
        compose.onNodeWithTag("rate_ms").performTextReplacement("250")
        compose.onNodeWithTag("rate_tps").assertTextContains("4")
        assertEquals(4.0, rate.tps, 1e-9)
    }

    @Test
    fun invalidValuesShowErrorAndAreNotReported() {
        setUp()
        val before = changes
        compose.onNodeWithTag("rate_tps").performTextReplacement("0")
        compose.onNodeWithTag("rate_error").assertIsDisplayed()
        compose.onNodeWithTag("rate_tps").performTextReplacement("35")
        compose.onNodeWithTag("rate_error").assertTextContains("Maximum is 20 TPS", substring = true)
        assertEquals(before, changes)
        assertEquals(TapRate.fromTps(5.0), rate)
    }

    @Test
    fun presetUpdatesBothFields() {
        setUp()
        compose.onNodeWithText("2 TPS").performClick()
        compose.onNodeWithTag("rate_ms").assertTextContains("500")
        compose.onNodeWithTag("rate_tps").assertTextContains("2")
        assertEquals(TapRate.fromTps(2.0), rate)
    }
}
