package zhiqiu.kongkim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import zhiqiu.kongkim.ui.HomeScreen
import zhiqiu.kongkim.ui.theme.KongKimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KongKimTheme {
                HomeScreen()
            }
        }
    }
}
