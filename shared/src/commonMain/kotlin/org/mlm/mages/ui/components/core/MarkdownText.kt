package org.mlm.mages.ui.components.core

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.mlm.mages.LocalMessageFontSize

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null
) {
    val fontSize = LocalMessageFontSize.current.sp
    val effectiveStyle = style.copy(fontSize = fontSize)

    SelectionContainer {
        Markdown(
            content = text,
            modifier = modifier,
            colors = markdownColor(
                text = color,
                inlineCodeBackground = MaterialTheme.colorScheme.onSurfaceVariant,
                codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7F),
                tableBackground = MaterialTheme.colorScheme.primary
            ),
            typography = markdownTypography(
                text = effectiveStyle,
                code = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSize.value * 0.9f).sp
                )
            )
        )
    }
}