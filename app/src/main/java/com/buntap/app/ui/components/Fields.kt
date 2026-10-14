package com.buntap.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.buntap.app.domain.NumberInput
import com.buntap.app.domain.Validated
import kotlin.math.roundToLong

/**
 * Integer input that only reports valid values. Invalid text stays visible with an error
 * message instead of being silently corrected.
 */
@Composable
fun IntField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    suffix: String? = null,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            when (val v = NumberInput.int(t, range.first, range.last, label)) {
                is Validated.Valid -> {
                    error = null
                    if (v.value != value) onValue(v.value)
                }
                is Validated.Invalid -> error = v.message
            }
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Duration input in seconds (decimals allowed), stored as milliseconds. */
@Composable
fun SecondsField(
    label: String,
    valueMs: Long,
    onValueMs: (Long) -> Unit,
    rangeMs: LongRange,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    var text by remember(valueMs) { mutableStateOf(Fmt.secondsValue(valueMs)) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val seconds = NumberInput.parseDouble(t)
            val ms = seconds?.let { (it * 1000).roundToLong() }
            error = when {
                t.isBlank() -> "Enter $label"
                ms == null -> "Not a valid number"
                ms < rangeMs.first -> "Minimum is ${Fmt.millis(rangeMs.first)}"
                ms > rangeMs.last -> "Maximum is ${Fmt.millis(rangeMs.last)}"
                else -> null
            }
            if (error == null && ms != null && ms != valueMs) onValueMs(ms)
        },
        label = { Text(label) },
        suffix = { Text("s") },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Milliseconds input for short timings. */
@Composable
fun MillisField(
    label: String,
    valueMs: Long,
    onValueMs: (Long) -> Unit,
    rangeMs: LongRange,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var text by remember(valueMs) { mutableStateOf(valueMs.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            when (val v = NumberInput.millis(t, rangeMs.first, rangeMs.last, label)) {
                is Validated.Valid -> {
                    error = null
                    if (v.value != valueMs) onValueMs(v.value)
                }
                is Validated.Invalid -> error = v.message
            }
        },
        label = { Text(label) },
        suffix = { Text("ms") },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** An optional integer limit: a switch turns it on, then the value can be edited. */
@Composable
fun OptionalIntField(
    title: String,
    description: String,
    value: Int?,
    onValue: (Int?) -> Unit,
    range: IntRange,
    defaultWhenEnabled: Int,
    fieldLabel: String,
) {
    Column {
        SwitchRow(title, checked = value != null, onCheckedChange = { onValue(if (it) defaultWhenEnabled else null) }, description = description)
        if (value != null) IntField(fieldLabel, value, { onValue(it) }, range)
    }
}

/** An optional duration in seconds. */
@Composable
fun OptionalSecondsField(
    title: String,
    description: String,
    valueMs: Long?,
    onValueMs: (Long?) -> Unit,
    rangeMs: LongRange,
    defaultWhenEnabledMs: Long,
    fieldLabel: String,
) {
    Column {
        SwitchRow(title, checked = valueMs != null, onCheckedChange = { onValueMs(if (it) defaultWhenEnabledMs else null) }, description = description)
        if (valueMs != null) SecondsField(fieldLabel, valueMs, { onValueMs(it) }, rangeMs)
    }
}

/** A labelled slider with a formatted value. */
@Composable
fun SliderField(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    steps: Int = 0,
    supporting: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.titleSmall)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range, steps = steps)
        supporting?.let { HelpText(it) }
    }
}

/** Single-choice segmented buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A read-only text field that opens a menu of choices. */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    supporting: String? = null,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            supportingText = supporting?.let { { Text(it) } },
            isError = isError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent layer that opens the menu (the text field itself is read-only).
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    expanded = false
                    onSelect(value)
                })
            }
        }
    }
}
