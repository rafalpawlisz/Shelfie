package io.github.rafalpawlisz.shelfie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.rafalpawlisz.shelfie.ui.ShelfieApp
import io.github.rafalpawlisz.shelfie.ui.theme.ShelfieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShelfieTheme {
                ShelfieApp()
            }
        }
    }
}
