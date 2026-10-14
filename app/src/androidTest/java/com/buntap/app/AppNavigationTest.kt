package com.buntap.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: launches the real app (real database, settings and services) and visits every
 * main screen, verifying each one renders without crashing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun skipOnboarding() {
        val container = (compose.activity.application as BunTapApplication).container
        if (container.settings.current.onboardingCompleted) return
        compose.waitUntilAtLeastOneExists(hasText("Skip"), 10_000)
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Quick actions"), 10_000)
    }

    private fun open(tab: String, expected: String) {
        compose.onNode(hasText(tab).and(androidx.compose.ui.test.hasClickAction()).and(androidx.compose.ui.test.isSelectable())).performClick()
        compose.waitUntilAtLeastOneExists(hasText(expected, substring = true), 10_000)
    }

    @Test
    fun everyMainScreenRenders() {
        compose.waitUntilAtLeastOneExists(hasText("Quick actions"), 10_000)
        open("Clicker", "Tap points")
        open("Targets", "Saved targets")
        open("Routines", "Example routine")
        open("Monitor", "Live")
        open("Home", "Quick actions")

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Safety limits"), 10_000)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasContentDescription("Permissions and diagnostics"), 10_000)
        compose.onNodeWithContentDescription("Permissions and diagnostics").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Accessibility service"), 10_000)
    }

    @Test
    fun newRoutineEditorOpens() {
        compose.waitUntilAtLeastOneExists(hasText("Quick actions"), 10_000)
        compose.onNodeWithText("New routine").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Defaults and limits"), 10_000)
        compose.onNodeWithText("Add action").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Tap burst"), 10_000)
    }
}
