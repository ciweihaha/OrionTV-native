package com.oriontv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.oriontv.navigation.OrionNavHost
import com.oriontv.ui.theme.OrionTheme
import dagger.hilt.android.AndroidEntryPoint

/** 单 Activity 入口，只做 TV 横屏。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                OrionNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
