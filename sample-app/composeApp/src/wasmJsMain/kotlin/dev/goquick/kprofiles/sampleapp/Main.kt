@file:OptIn(ExperimentalComposeUiApi::class)

package dev.goquick.kprofiles.sampleapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("root") {
        App()
    }
}
