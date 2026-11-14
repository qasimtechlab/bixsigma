package ai.bixsigma.mobile

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.bixsigma.mobile.ui.BixSigmaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(7, 11, 22)
        window.navigationBarColor = AndroidColor.rgb(7, 11, 22)
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { BixSigmaApp() }
    }
}
