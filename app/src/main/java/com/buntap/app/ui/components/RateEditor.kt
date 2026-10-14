package com.buntap.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.buntap.app.domain.NumberInput
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.Validated
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RatePresets
import com.buntap.app.domain.model.TapRate
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Edits a tapping rate as taps per second AND as a millisecond interval. Editing either field
 * updates the other (interval = 1000 / TPS). Invalid input is shown with an explanation and
 * never reported upwards, so callers only ever receive valid rates.
 */
@Composable
fun RateEditor(
    rate: TapRate,
    limits: RateLimits,
    onRateChange: (TapRate) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Tapping rate",
    observedTps: Double? = null,
    enabled: Boolean = true,
) {
    var tpsText by rememberSaveable { mutableStateOf(RateValidator.formatTps(rate.tps)) }
    var msText by rememberSaveable { mutableStateOf(RateValidator.formatMs(rate.intervalMs)) }
    var error by remember { mutableStateOf(RateValidator.check(rate, limits)) }

    // External changes (slider, preset, loading data) refresh whichever field doesn't show the
    // new rate yet, without overwriting what the user is typing.
    LaunchedEffect(rate, limits) {
        if (parseRaw(tpsText, fromTps = true) != rate) tpsText = RateValidator.formatTps(rate.tps)
        if (parseRaw(msText, fromTps = false) != rate) msText = RateValidator.formatMs(rate.intervalMs)
        error = RateValidator.check(rate, limits)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = tpsText,
                onValueChange = { t ->
                    tpsText = t
                    when (val v = RateValidator.parseTps(t, limits)) {
                        is Validated.Valid -> {
                            error = null
                            msText = RateValidator.formatMs(v.value.intervalMs)
                            if (v.value != rate) onRateChange(v.value)
                        }
                        is Validated.Invalid -> error = v.message
                    }
                },
                label = { Text("Taps per second") },
                suffix = { Text("TPS") },
                singleLine = true,
                enabled = enabled,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("rate_tps"),
            )
            OutlinedTextField(
                value = msText,
                onValueChange = { t ->
                    msText = t
                    when (val v = RateValidator.parseIntervalMs(t, limits)) {
                        is Validated.Valid -> {
                            error = null
                            tpsText = RateValidator.formatTps(v.value.tps)
                            if (v.value != rate) onRateChange(v.value)
                        }
                        is Validated.Invalid -> error = v.message
                    }
                },
                label = { Text("Interval") },
                suffix = { Text("ms") },
                singleLine = true,
                enabled = enabled,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("rate_ms"),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rate_error"))
        }

        val minTps = limits.minTps
        val maxTps = limits.effectiveMaxTps
        Slider(
            value = sliderPosition(rate.tps, minTps, maxTps),
            onValueChange = { p -> onRateChange(TapRate.fromTps(niceTps(sliderTps(p, minTps, maxTps), minTps, maxTps))) },
            enabled = enabled,
            modifier = Modifier.testTag("rate_slider"),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatePresets.tpsValues.filter { it <= maxTps + 1e-9 }.forEach { preset ->
                val presetRate = TapRate.fromTps(preset)
                FilterChip(
                    selected = presetRate == rate,
                    onClick = { onRateChange(presetRate) },
                    label = { Text("${RateValidator.formatTps(preset)} TPS") },
                    enabled = enabled,
                )
            }
        }
        HelpText(
            "Requested: ${RateValidator.formatTps(rate.tps)} TPS = one tap every ${RateValidator.formatMs(rate.intervalMs)} ms" +
                (observedTps?.let { " · Observed now: ${RateValidator.formatTps(it)} TPS" } ?: "") +
                ". This is the requested schedule; Android may deliver taps more slowly, so BunTap measures the real rate while running. " +
                "Allowed: ${RateValidator.formatTps(minTps)}–${RateValidator.formatTps(maxTps)} TPS.",
        )
    }
}

private fun parseRaw(text: String, fromTps: Boolean): TapRate? {
    val v = NumberInput.parseDouble(text) ?: return null
    if (v <= 0) return null
    return if (fromTps) TapRate.fromTps(v) else TapRate.fromIntervalMs(v)
}

/** Logarithmic slider so low rates (0.1–2 TPS) remain easy to choose. */
private fun sliderPosition(tps: Double, min: Double, max: Double): Float {
    if (max <= min) return 0f
    return (ln(tps.coerceIn(min, max) / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)
}

private fun sliderTps(position: Float, min: Double, max: Double): Double = min * (max / min).pow(position.toDouble())

/** Rounds slider values to friendly numbers. */
private fun niceTps(tps: Double, min: Double, max: Double): Double {
    val rounded = when {
        tps >= 10 -> (tps * 2).roundToInt() / 2.0
        tps >= 1 -> (tps * 10).roundToInt() / 10.0
        else -> (tps * 20).roundToInt() / 20.0
    }
    return rounded.coerceIn(min, max)
}
