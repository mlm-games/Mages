package org.mlm.mages.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.QrMatrix

@Composable
fun QrCodeImage(
    matrix: QrMatrix,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        if (matrix.size <= 0) return@Canvas
        val cell = size.minDimension / matrix.size
        val originX = (size.width - cell * matrix.size) / 2f
        val originY = (size.height - cell * matrix.size) / 2f
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isDark(x, y)) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(originX + x * cell, originY + y * cell),
                    size = Size(cell + 0.35f, cell + 0.35f),
                )
            }
        }
    }
}
