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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box

private val EditorFont = FontFamily.Monospace
private val EditorSize = 13.sp
private val EditorLineHeight = 19.sp
private val EditorLines = 18

/**
 * INI highlighting for the plugin config editor: comments, sections, keys and
 * values get their own colours, and a commented plugin name stays readable so
 * it is obvious which entry is switched off.
 *
 * The text is copied verbatim — only styles are attached — so the visual
 * transformation keeps a 1:1 offset mapping. Rebuilding the line (trimming,
 * collapsing spaces around '=') changes the length and Compose rejects the
 * identity mapping, which crashes the editor.
 */
private fun highlightIni(text: String): AnnotatedString = buildAnnotatedString {
    var pos = 0
    while (pos <= text.length) {
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        val line = text.substring(pos, lineEnd)

        var first = 0
        while (first < line.length && line[first] == ' ') first++

        fun style(from: Int, to: Int, span: SpanStyle) {
            if (to > from) addStyle(span, pos + from, pos + to)
        }

        if (first < line.length) {
            when {
                line[first] == ';' -> {
                    style(first, line.length, SpanStyle(color = Gray60, fontStyle = FontStyle.Italic))
                    // keep the name after ';' readable so an off plugin is obvious
                    var nameStart = first + 1
                    if (nameStart < line.length && line[nameStart] == ' ') nameStart++
                    var nameEnd = nameStart
                    while (nameEnd < line.length && line[nameEnd] != ' ' && line[nameEnd] != ';') nameEnd++
                    if (nameEnd > nameStart && line.getOrNull(nameEnd) != ';') {
                        style(first + 1, nameStart, SpanStyle(color = Gray60, fontStyle = FontStyle.Italic))
                        style(nameStart, nameEnd, SpanStyle(color = Tertiary))
                    }
                }
                line[first] == '[' -> {
                    style(first, line.length, SpanStyle(color = Accent, fontWeight = FontWeight.Bold))
                }
                else -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val semi = line.indexOf(';', eq + 1)
                        val valueEnd = if (semi > eq) semi else line.length
                        style(first, eq, SpanStyle(color = White))
                        style(eq, eq + 1, SpanStyle(color = Gray70))
                        var valueStart = eq + 1
                        while (valueStart < valueEnd && line[valueStart] == ' ') valueStart++
                        val value = line.substring(valueStart, valueEnd)
                        style(
                            valueStart,
                            valueEnd,
                            SpanStyle(color = if (value.endsWith(".amxx")) SuccessGreen else Secondary),
                        )
                        if (semi > eq) {
                            style(semi, line.length, SpanStyle(color = Gray60, fontStyle = FontStyle.Italic))
                        }
                    }
                }
            }
        }
        append(line)
        if (lineEnd < text.length) {
            append('\n')
            pos = lineEnd + 1
        } else {
            pos = text.length + 1
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
    val pageScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    LaunchedEffect(Unit) { vm.loadPluginInis() }
    LaunchedEffect(inis) {
        if (selected == null || inis.none { it.file == selected }) {
            selected = inis.firstOrNull()?.file
        }
    }
    LaunchedEffect(selected) { selected?.let(vm::openPluginIni) }

    val file = selected
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val editorStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = EditorFont,
        fontSize = EditorSize,
        lineHeight = EditorLineHeight,
    )
    val saved = savedAt > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(pageScroll)
            .imePadding()
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The viewport is a fixed 18 lines; scrolling happens inside it in
                // both directions, and the gutter scrolls with the text through the
                // same state.
                val editorHeight = (EditorLines * EditorLineHeight.value).dp + 16.dp
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(editorHeight)
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Column(horizontalAlignment = Alignment.End) {
                            repeat(lineCount) { i ->
                                Text(
                                    "${i + 1}",
                                    style = editorStyle.copy(color = Gray70),
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        // Measured with unbounded width, so long lines stay on one
                        // line instead of soft-wrapping.
                        BasicTextField(
                            value = text,
                            onValueChange = vm::editIniText,
                            textStyle = editorStyle.copy(color = White),
                            cursorBrush = SolidColor(Accent),
                            visualTransformation = IniHighlight(),
                            modifier = Modifier.widthIn(min = 240.dp),
                        )
                    }
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
