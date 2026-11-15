package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * A reusable mark for "I chose this by guessing".
 *
 * It stays available after the choice is made, so a learner who only recognizes the guess on seeing
 * the key can still record the fact. The mark is only a flag; [PracticeAnswer.guessed] carries it to
 * the library, where a right guess keeps its review row instead of being treated as mastery.
 */
@Composable
internal fun GuessMark(
    guessed: Boolean,
    enabled: Boolean,
    onGuessed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = guessed,
            onClick = { onGuessed(!guessed) },
            enabled = enabled,
            label = { Text("这题是猜的") },
            modifier = Modifier.testTag("guess-mark"),
        )
        if (guessed) Text("猜对也会排进复习，不算掌握。", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(220.dp))
    }
}
