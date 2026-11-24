package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** The time in ms, read again as each of [untils] passes, so a pause that ends stops showing. */
@Composable
fun rememberPushNow(untils: List<Long?>): Long {
    val date = remember { dependency(DateClient) }
    var now by remember { mutableLongStateOf(date.now().toEpochMilli()) }
    LaunchedEffect(untils) {
        now = date.now().toEpochMilli()
        while (true) {
            var next: Long? = null
            for (until in untils) {
                if (until != null && until > now && (next == null || until < next)) next = until
            }
            if (next == null) break
            delay(next - now)
            now = date.now().toEpochMilli()
        }
    }
    return now
}
