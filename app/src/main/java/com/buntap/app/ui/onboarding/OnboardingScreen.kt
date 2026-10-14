package com.buntap.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.LocalAppContainer
import com.buntap.app.ui.navigation.Navigator
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val points: List<String>)

private val pages = listOf(
    Page(
        BunIcons.TouchApp,
        "Welcome to BunTap",
        listOf(
            "Auto clicker: tap one or many screen positions at a precise rate (taps per second or milliseconds), a fixed number of times or until you stop.",
            "Visual targets: save a picture of a button and let BunTap tap it when it appears on screen.",
            "Routines: build step-by-step automations with waits, conditions, loops and retries.",
            "Floating panel: start, pause and stop automation on top of other apps.",
        ),
    ),
    Page(
        BunIcons.Speed,
        "How it works — and its limits",
        listOf(
            "Taps are performed through Android's accessibility gesture API. No root is needed.",
            "The rate you choose is a requested schedule. Android may deliver taps more slowly; BunTap shows the real, measured rate.",
            "“Completed” means Android finished the gesture — not that the app underneath reacted. Some apps ignore automated taps.",
            "Screens that apps mark as protected (banking, DRM video) appear black in captures and cannot be recognised.",
            "Battery savers and some phone makers may stop background apps. Automation can't run forever.",
        ),
    ),
    Page(
        BunIcons.Shield,
        "Permissions BunTap asks for",
        listOf(
            "Accessibility service (required for tapping): you switch it on yourself in Android Settings.",
            "Screen capture (only for visual targets): Android asks every time you start it, and it stops when the screen locks.",
            "Display over other apps (optional): for the floating panel and the on-screen point picker.",
            "Notifications (optional): a status notification with Pause and Stop buttons.",
        ),
    ),
    Page(
        BunIcons.Bolt,
        "Privacy and safety",
        listOf(
            "Everything stays on this phone. BunTap has no internet permission, no accounts, no ads and no analytics.",
            "Only the cropped target images you save are stored; full screenshots are deleted after cropping.",
            "Emergency stop, safety limits (maximum TPS, taps and duration) and confirmations keep you in control.",
            "Respect the rules of the apps you automate. BunTap does not bypass security or anti-cheat protections.",
        ),
    ),
)

@Composable
fun OnboardingScreen(nav: Navigator) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val finish: () -> Unit = {
        scope.launch {
            container.settings.update { it.copy(onboardingCompleted = true) }
            nav.finishOnboarding()
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / ${pages.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = finish) { Text("Skip") }
            }
            AnimatedContent(targetState = page, label = "onboarding", modifier = Modifier.weight(1f)) { index ->
                val p = pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.size(16.dp))
                    if (index == 0) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.buntap.app.R.drawable.buntap_logo),
                            contentDescription = "BunTap logo",
                            modifier = Modifier.size(160.dp).align(Alignment.CenterHorizontally),
                        )
                        Text(
                            "by bensberry",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else Box(
                        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                    }
                    Text(p.title, style = MaterialTheme.typography.headlineSmall)
                    p.points.forEach { point ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(point, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Back") }
                if (page < pages.lastIndex) {
                    Button(onClick = { page++ }) { Text("Next") }
                } else {
                    Button(onClick = finish) { Text("Get started") }
                }
            }
        }
    }
}
