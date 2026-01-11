package com.metalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metalens.app.R
import com.metalens.app.history.ConversationHistoryRecord
import com.metalens.app.history.ConversationHistoryRole
import com.metalens.app.history.ConversationHistoryStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryDetailScreen(
    conversationId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val storage = remember { ConversationHistoryStorage(context) }
    var record by remember { mutableStateOf<ConversationHistoryRecord?>(null) }

    LaunchedEffect(conversationId) {
        record = storage.getConversation(conversationId)
    }

    val r = record
    if (r == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.history_detail_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = formatStartedAt(r.startedAtMs),
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = r.messages, key = { "${conversationId}:${it.role}:${it.text.hashCode()}" }) { msg ->
                HistoryMessageBubble(
                    isUser = msg.role == ConversationHistoryRole.User,
                    text = msg.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HistoryMessageBubble(
    isUser: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Column(
        modifier = modifier,
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (isUser) "You" else "AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.9f).heightIn(min = 40.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private fun formatStartedAt(startedAtMs: Long): String {
    val dt =
        Instant.ofEpochMilli(startedAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(dt)
}

