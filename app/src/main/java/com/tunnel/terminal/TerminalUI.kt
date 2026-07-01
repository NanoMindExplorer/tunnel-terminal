package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TabBar(
    tabs: List<Pair<Int, Int>>, // Pair of (Session ID, Tab Number)
    activeTabId: Int,
    onTabSelected: (Int) -> Unit,
    onNewTab: () -> Unit,
    onTabClosed: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs) { tab ->
            val isActive = tab.first == activeTabId
            Row(
                modifier = Modifier
                    .background(
                        if (isActive) Color(0xFF333333) else Color(0xFF222222),
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onTabSelected(tab.first) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tab ${tab.second}  ",
                    color = if (isActive) Color.White else Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                // Tombol Close (X)
                Box(
                    modifier = Modifier
                        .clickable { onTabClosed(tab.first) }
                        .padding(4.dp)
                ) {
                    Text(
                        text = "X",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        item {
            Box(
                modifier = Modifier
                    .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                    .clickable { onNewTab() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text("+", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ExtraKeysBar(
    onKeyPressed: (String) -> Unit
) {
    val keys = listOf("ESC", "TAB", "CTRL", "ALT", "-", "/", "|", "↑", "↓", "←", "→")
    
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2B2B))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(keys) { key ->
            Box(
                modifier = Modifier
                    .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                    .clickable { onKeyPressed(key) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = key,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
