package com.coach.desktop.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ShelfPane(session: ReaderSession) {
    val remote = session.remote
    if (remote != null) {
        val scope = rememberCoroutineScope()
        Column {
            Text("书架", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            remote.failure?.let { Text(it.text, Modifier.testTag("shelf-failure").padding(top = 6.dp), color = Color(0xFFA33B2D), fontSize = 12.sp) }
            for (d in remote.documents) {
                Text(
                    d.title,
                    Modifier.testTag("doc-${d.id}").fillMaxWidth().clickable { scope.launch { session.openRemote(d.id) } }.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = if (remote.current?.id == d.id) Accent else Ink,
                    fontWeight = if (remote.current?.id == d.id) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        return
    }
    Column {
        Text("书架", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(session.fixture.title, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
