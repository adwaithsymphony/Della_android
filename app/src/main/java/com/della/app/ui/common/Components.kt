@file:OptIn(ExperimentalMaterial3Api::class)

package com.della.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.della.app.ui.theme.Della

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 22) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("DELLA", fontFamily = FontFamily.Serif, fontSize = size.sp, letterSpacing = (size * 0.28).sp, color = Della.Text)
        Box(Modifier.padding(start = 3.dp).size((size * 0.26).dp).background(Della.Accent, CircleShape))
    }
}

@Composable
fun DellaTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Della.TextMuted)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Della.Background, scrolledContainerColor = Della.Background),
    )
}

@Composable
fun BrandTopBar(subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(size = 18)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Della.TextMuted, maxLines = 1)
        } },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Della.Background),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(Della.Panel, shape)
            .border(BorderStroke(1.dp, Della.Border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Della.TextMuted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = Della.Accent, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, subColor: Color = Della.TextMuted, onClick: (() -> Unit)? = null) {
    Panel(modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Della.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Della.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor, maxLines = 2)
        }
    }
}

@Composable
fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Della.Text, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Della.TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun Pill(text: String, color: Color = Della.Accent) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun DemoBanner(label: String) {
    Row(
        Modifier.fillMaxWidth().background(Della.PanelHigh).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Della.Warning, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("DEMONSTRATION DATA · $label", style = MaterialTheme.typography.labelSmall, color = Della.Warning)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Della.Primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Della.Text)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Della.TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(action, onClick = onAction)
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Della.Primary, contentColor = Della.Text, disabledContainerColor = Della.PanelHigh, disabledContentColor = Della.TextFaint),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Della.Accent, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else Della.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Della.Accent, unfocusedBorderColor = Della.Border,
    focusedLabelColor = Della.Accent, unfocusedLabelColor = Della.TextMuted,
    cursorColor = Della.Accent, focusedContainerColor = Della.Panel, unfocusedContainerColor = Della.Panel,
)

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, supporting: String? = null,
    isError: Boolean = false, prefix: String? = null, minLines: Int = 1, enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = singleLine, minLines = minLines, isError = isError, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        prefix = if (prefix != null) { { Text(prefix, color = Della.TextMuted) } } else null,
        supportingText = if (supporting != null) { { Text(supporting) } } else null,
        colors = fieldColors(), shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun MoneyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Field(value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, label, modifier, KeyboardType.Decimal, prefix = "₹ ", supporting = supporting,
        isError = value.isNotEmpty() && com.della.app.domain.Money.parse(value) == null)
}

@Composable
fun QtyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, allowNegative: Boolean = false, supporting: String? = null) {
    Field(value, { v -> onChange(v.filterIndexed { i, c -> c.isDigit() || (allowNegative && c == '-' && i == 0) }) }, label, modifier, KeyboardType.Number, supporting = supporting)
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(placeholder, color = Della.TextFaint) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Della.TextMuted) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear") } },
        colors = fieldColors(), shape = RoundedCornerShape(12.dp),
    )
}

/** Dropdown selector without experimental APIs. */
@Composable
fun <T> SelectField(label: String, options: List<T>, selected: T?, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Select") {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = selected?.let(display) ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            placeholder = { Text(placeholder, color = Della.TextFaint) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(12.dp), singleLine = true,
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Della.PanelHigh)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(options) { o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Della.Panel, labelColor = Della.TextMuted,
                    selectedContainerColor = Della.Primary, selectedLabelColor = Della.Text,
                ),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = o == selected, borderColor = Della.Border, selectedBorderColor = Della.Primary),
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = false, content: (@Composable ColumnScope.() -> Unit)? = null, confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Della.Panel,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Della.TextMuted)
                if (content != null) { Spacer(Modifier.height(12.dp)); content() }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm, color = if (!confirmEnabled) Della.TextFaint else if (destructive) Della.Danger else Della.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Della.TextMuted) } },
    )
}

@Composable
fun ListRow(
    title: String, subtitle: String? = null, trailing: String? = null, trailingSub: String? = null,
    trailingColor: Color = Della.Text, leading: ImageVector? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(36.dp).background(Della.PanelHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(leading, null, tint = Della.Accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Della.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(trailing, style = MaterialTheme.typography.bodyMedium, color = trailingColor, fontWeight = FontWeight.SemiBold)
                if (trailingSub != null) Text(trailingSub, style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
            }
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Della.Border, thickness = 1.dp)

@Composable
fun ErrorList(errors: List<String>, color: Color = Della.Danger) {
    if (errors.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = color) }
    }
}
