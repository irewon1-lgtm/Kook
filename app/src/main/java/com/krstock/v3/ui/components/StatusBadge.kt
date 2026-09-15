package com.krstock.v3.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.DataStatus

@Composable
fun StatusBadge(status: DataStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (status) {
        DataStatus.REAL -> scheme.secondaryContainer to scheme.onSecondaryContainer
        DataStatus.REGISTERED -> scheme.primaryContainer to scheme.onPrimaryContainer
        DataStatus.DEMO -> scheme.surfaceVariant to scheme.onSurfaceVariant
        DataStatus.MISSING -> scheme.errorContainer to scheme.onErrorContainer
        DataStatus.WAITING_FOR_AUTH -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    val label = when (status) {
        DataStatus.REAL -> "실데이터"
        DataStatus.REGISTERED -> "등록"
        DataStatus.DEMO -> "데모"
        DataStatus.MISSING -> "결측"
        DataStatus.WAITING_FOR_AUTH -> "연결 대기"
    }

    Surface(
        modifier = modifier,
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
