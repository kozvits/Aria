package app.aria.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.aria.ui.App
import java.awt.Dimension

fun main() = application {
    val state = rememberWindowState(width = 1180.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Aria — виртуальная ассистентка",
        state = state,
    ) {
        window.minimumSize = Dimension(420, 620)
        App()
    }
}
