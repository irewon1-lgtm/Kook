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
import com.krstock.v3.ui.theme.EmeraldGreen
import com.krstock.v3.ui.theme.RoseError

@Composable
fun StatusBadge(status: DataStatus, modifier: Modifier = Modifier) {
    val (bgColor, textColor, label) = when (status) {
        DataStatus.REAL -> Triple(EmeraldGreen.copy(alpha = 0.15f), EmeraldGreen, "REAL")
        DataStatus.DEMO -> Triple(Color.Gray.copy(alpha = 0.15f), Color.DarkGray, "DEMO")
        DataStatus.MISSING -> Triple(RoseError.copy(alpha = 0.15f), RoseError, "MISSING")
        DataStatus.WAITING_FOR_AUTH -> Triple(AmberWarning.copy(alpha = 0.15f), AmberWarning, "WAITING_FOR_AUTH")
    }

    Box(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
