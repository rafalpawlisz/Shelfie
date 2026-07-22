package io.github.rafalpawlisz.shelfie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryScreen
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.theme.ShelfieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShelfieTheme {
                PantryScreen(viewModel = viewModel(factory = PantryViewModel.Factory))
            }
        }
    }
}
