package com.e5lab.accamera.desktop

import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp

fun main() = application {
    val repo = DesktopRepository(DesktopRepository.defaultFile())
    Window(
        onCloseRequest = ::exitApplication,
        title = "AC-Camera 等声压级测量（桌面端）",
        state = rememberWindowState(width = 1180.dp, height = 780.dp),
    ) {
        App(repo)
    }
}
