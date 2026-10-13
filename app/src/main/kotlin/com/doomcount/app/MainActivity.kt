package com.doomcount.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.doomcount.app.ui.MainScreen
import com.doomcount.app.ui.theme.DoomcountTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoomcountTheme {
                MainScreen()
            }
        }
    }
}

