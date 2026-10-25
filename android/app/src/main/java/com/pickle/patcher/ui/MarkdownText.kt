package com.pickle.patcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val lines = text.split("\n")
    var inCode = false
    val codeBuf = StringBuilder()
    Column(modifier = modifier) {
        for (line in lines) {
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    // render code block
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            codeBuf.toString().trimEnd('\n'),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = color,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    codeBuf.clear()
                    inCode = false
                } else {
                    inCode = true
                }
                continue
            }
            if (inCode) {
                codeBuf.append(line).append("\n")
                continue
            }
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(trimmed.removePrefix("### "), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
                trimmed.startsWith("## ") -> Text(trimmed.removePrefix("## "), style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                trimmed.startsWith("# ") -> Text(trimmed.removePrefix("# "), style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row {
                    Text("• ", color = color)
                    Text(parseInline(trimmed.substring(2)), color = color)
                }
                trimmed.startsWith("> ") -> Text(parseInline(trimmed.removePrefix("> ")), color = color, fontStyle = FontStyle.Italic)
                else -> Text(parseInline(trimmed), color = color)
            }
        }
        if (inCode && codeBuf.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(codeBuf.toString().trimEnd('\n'), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}
