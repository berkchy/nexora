package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray85
import com.pickle.patcher.ui.theme.Secondary
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.Tertiary
import com.pickle.patcher.ui.theme.White
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontStyle
import com.pickle.patcher.ui.theme.Gray99
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.OffsetMapping

private val EditorFont = FontFamily.Monospace
private val EditorSize = 12.sp
private val EditorLineHeight = 17.sp

/**
 * INI highlighting for the plugin config editor: comments, sections, keys and
 * values get their own colours, and a commented plugin name stays readable so
 * it is obvious which entry is switched off.
 */
private fun highlightIni(text: String): AnnotatedString = buildAnnotatedString {
    text.split("\n").forEachIndexed { index, line ->
        if (index > 0) append("\n")
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        if (indent > 0) append(" ".repeat(indent))
        when {
            trimmed.isEmpty() -> Unit
            trimmed.startsWith(";") -> {
                val name = trimmed.removePrefix(";").trim()
                if (name.isEmpty() || name.startsWith(";") || name.endsWith(";")) {
                    withStyle(SpanStyle(color = Gray60, fontStyle = FontStyle.Italic)) {
                        append(trimmed)
                    }
                } else {
                    withStyle(SpanStyle(color = Gray60, fontStyle = FontStyle.Italic)) {
                        append(";")
                    }
                    withStyle(SpanStyle(color = Tertiary)) { append(name) }
                    val rest = trimmed.removePrefix(";").removePrefix(name)
                    if (rest.isNotEmpty()) {
                        withStyle(SpanStyle(color = Gray60, fontStyle = FontStyle.Italic)) {
                            append(rest)
                        }
                    }
                }
            }
            trimmed.startsWith("[") -> withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) {
                append(trimmed)
            }
            else -> {
                val eq = line.indexOf('=')
                val semi = line.indexOf(';')
                if (eq > 0) {
                    val valueEnd = if (semi > eq) semi else line.length
                    append(line.substring(0, eq).trimEnd())
                    withStyle(SpanStyle(color = Gray70)) { append(" = ") }
                    val value = line.substring(eq + 1, valueEnd).trim()
                    withStyle(
                        SpanStyle(
                            color = if (value.endsWith(".amxx")) SuccessGreen else Secondary,
                        )
                    ) { append(value) }
                    if (semi > eq) {
                        withStyle(SpanStyle(color = Gray60, fontStyle = FontStyle.Italic)) {
                            append(line.substring(semi))
                        }
                    }
                } else {
                    append(trimmed)
                }
            }
        }
    }
}

private class IniHighlight : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightIni(text.text), OffsetMapping.Identity)
}

@Composable
fun PluginsScreen(vm: PatcherViewModel) {
    val inis by vm.pluginInis.collectAsState()
    val text by vm.iniText.collectAsState()
    val dirty by vm.iniDirty.collectAsState()
    val savedAt by vm.iniSavedAt.collectAsState()
    var selected by remember { mutableStateOf<File?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.loadPluginInis() }
    LaunchedEffect(inis) {
        if (selected == null || inis.none { it.file == selected }) {
            selected = inis.firstOrNull()?.file
        }
    }
    LaunchedEffect(selected) { selected?.let(vm::openPluginIni) }

    val file = selected
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val saved = savedAt > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Plugins", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            file?.name ?: "No plugins-*.ini found. Set the game folder on the Addons tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (file != null) Gray40 else Tertiary,
            maxLines = 1,
        )

        if (inis.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(inis) { ini ->
                    val active = ini.file == file
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) Accent.copy(alpha = 0.14f) else Gray85,
                        modifier = Modifier.clickable { selected = ini.file },
                    ) {
                        Text(
                            ini.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) Accent else Gray40,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (file != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Gray99,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    // line-number gutter
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.width(34.dp),
                    ) {
                        repeat(lineCount) { i ->
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = EditorFont,
                                    fontSize = EditorSize,
                                    lineHeight = EditorLineHeight,
                                ),
                                color = Gray70,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = text,
                        onValueChange = vm::editIniText,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = White,
                            fontFamily = EditorFont,
                            fontSize = EditorSize,
                            lineHeight = EditorLineHeight,
                        ),
                        cursorBrush = SolidColor(Accent),
                        visualTransformation = IniHighlight(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        dirty -> "unsaved changes"
                        saved -> "saved"
                        else -> file.name
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        dirty -> Tertiary
                        saved -> SuccessGreen
                        else -> Gray40
                    },
                    modifier = Modifier.weight(1f),
                )
                if (dirty) {
                    SecondaryButton("Revert", onClick = { vm.revertPluginIni(file) })
                    Spacer(Modifier.width(8.dp))
                }
                PrimaryButton(
                    text = "Save",
                    onClick = { vm.savePluginIni(file) },
                    enabled = dirty,
                    icon = { Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Restart the game for changes to take effect.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray60,
            )
        }
    }
}
