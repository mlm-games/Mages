package org.mlm.mages.ui.components.core

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor // Ensure this import is present
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
    val baseStyle = style.copy(fontSize = fontSize)
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = (fontSize.value * 0.9f).sp
    )

    SelectionContainer {
        Markdown(
            content = text,
            modifier = modifier,
            colors = markdownColor(
                text = color,
                codeBackground = color.copy(alpha = 0.1f),
                inlineCodeBackground = color.copy(alpha = 0.1f)
            ),
            typography = markdownTypography(
                h1 = baseStyle.copy(fontSize = (fontSize.value * 2.0f).sp, fontWeight = FontWeight.Bold),
                h2 = baseStyle.copy(fontSize = (fontSize.value * 1.8f).sp, fontWeight = FontWeight.Bold),
                h3 = baseStyle.copy(fontSize = (fontSize.value * 1.6f).sp, fontWeight = FontWeight.Bold),
                h4 = baseStyle.copy(fontSize = (fontSize.value * 1.4f).sp, fontWeight = FontWeight.Bold),
                h5 = baseStyle.copy(fontSize = (fontSize.value * 1.2f).sp, fontWeight = FontWeight.Bold),
                h6 = baseStyle.copy(fontSize = (fontSize.value * 1.1f).sp, fontWeight = FontWeight.Bold),
                text = baseStyle,
                paragraph = baseStyle,
                bullet = baseStyle,
                ordered = baseStyle,
                list = baseStyle,
                quote = baseStyle,
                table = baseStyle,
                inlineCode = codeStyle,
                code = codeStyle,
                textLink = TextLinkStyles(
                    style = baseStyle.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline
                    ).toSpanStyle()
                )
            )
        )
    }
}