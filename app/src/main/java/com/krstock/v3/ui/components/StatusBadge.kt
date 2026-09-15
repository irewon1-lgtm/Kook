package com.krstock.v3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.DataStatus
import com.krstock.v3.ui.theme.AmberWarning
import com.krstock.v3.ui.theme.BlueAccent
import com.krstock.v3.ui.theme.EmeraldGreen
import com.krstock.v3.ui.theme.RoseError

@Composable
fun StatusBadge(status: DataStatus, modifier: Modifier = Modifier) {
    val (bgColor, textColor, label) = when (status) {
        DataStatus.REAL -> Triple(EmeraldGreen.copy(alpha = 0.12f), EmeraldGreen, "실데이터")
        DataStatus.REGISTERED -> Triple(BlueAccent.copy(alpha = 0.10f), BlueAccent, "등록")
        DataStatus.DEMO -> Triple(Color.Gray.copy(alpha = 0.12f), Color.DarkGray, "데모")
        DataStatus.MISSING -> Triple(RoseError.copy(alpha = 0.11f), RoseError, "결측")
        DataStatus.WAITING_FOR_AUTH -> Triple(AmberWarning.copy(alpha = 0.11f), AmberWarning, "연결 대기")
    }

    Box(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
