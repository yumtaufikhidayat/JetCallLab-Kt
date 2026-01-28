package id.yumtaufikhidayat.jetcalllab

import android.app.ComponentCaller
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import id.yumtaufikhidayat.jetcalllab.ext.handleIntent
import id.yumtaufikhidayat.jetcalllab.ui.screen.CallScreen
import id.yumtaufikhidayat.jetcalllab.ui.theme.JetCallLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.handleIntent()
        setContent {
            JetCallLabTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CallScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        android.util.Log.d("MainActivity", "onNewIntent action=${intent.action}")
    }
}