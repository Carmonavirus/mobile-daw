// Entry point of the web build: loads the saved files, fonts and sound engine, then shows the
// APK's own screens (DawTheme { DawApp(vm) }, as MainActivity does).
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.ui.DawApp
import com.mobiledaw.ui.theme.DawTheme
import com.mobiledaw.ui.theme.ThemeStore
import com.mobiledaw.web.Vfs
import com.mobiledaw.web.WebApp
import com.mobiledaw.web.WebFonts
import com.mobiledaw.web.toKotlin
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume

private external fun engineInit(done: (String?) -> Unit)
private external fun preloadAll(done: () -> Unit)
private external fun fontBytes(name: String): Int8Array?
private external fun windowWidth(): Int
private external fun windowHeight(): Int
private external fun onWindowResize(cb: () -> Unit)
private external fun onPageVisibility(cb: (Boolean) -> Unit)
private external fun hideSplash()
private external fun showStartError(msg: String)

private var probeVm: DawViewModel? = null
private var probeText = ""

/** For automated checks (web/app/tests): a few facts about the running app, as JSON. */
private fun dawProbe(): String {
    val vm = probeVm ?: return "{}"
    val p = vm.project
    return "{\"name\":\"${p.name}\",\"channels\":${p.channels.size},\"playing\":${vm.playing},\"pos\":${com.mobiledaw.audio.NativeEngine.positionTicks()},$probeText}"
}

private external fun registerProbe(probe: () -> String, togglePlay: () -> Unit)

private fun currentConfig() = Configuration(windowWidth(), windowHeight())

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        Vfs.load()
        suspendCancellableCoroutine { c -> preloadAll { c.resume(Unit) } }
        val error = suspendCancellableCoroutine { c -> engineInit { c.resume(it) } }
        if (error != null) {
            showStartError("Sound couldn't start in this browser: $error")
            return@launch
        }
        val regular = fontBytes("Roboto-Regular.ttf")?.toKotlin()
        val medium = fontBytes("Roboto-Medium.ttf")?.toKotlin()
        val bold = fontBytes("Roboto-Bold.ttf")?.toKotlin()
        WebFonts.regular = regular
        WebFonts.bold = bold
        val app = WebApp.context
        ThemeStore.load(app)
        val vm = DawViewModel(app)
        probeVm = vm
        registerProbe(::dawProbe) { vm.togglePlay() }
        val config = mutableStateOf(currentConfig())
        onWindowResize { config.value = currentConfig() }
        onPageVisibility { visible -> if (visible) vm.onResume() else vm.onPause() }
        val fonts = FontFamily(
            listOfNotNull(
                regular?.let { Font("Roboto", it, FontWeight.Normal) },
                medium?.let { Font("Roboto-Medium", it, FontWeight.Medium) },
                (medium ?: regular)?.let { Font("Roboto-SemiBold", it, FontWeight.SemiBold) },
                bold?.let { Font("Roboto-Bold", it, FontWeight.Bold) },
            ),
        )
        hideSplash()
        ComposeViewport(document.body!!) {
            val resolver = LocalFontFamilyResolver.current
            val measurer = rememberTextMeasurer()
            LaunchedEffect(Unit) {
                resolver.preload(fonts)
                val plain = measurer.measure("Mobile DAW", TextStyle(fontSize = 20.sp)).size
                val roboto = measurer.measure("Mobile DAW", TextStyle(fontSize = 20.sp, fontFamily = fonts)).size
                probeText = "\"textW\":${plain.width},\"textH\":${plain.height},\"robotoW\":${roboto.width}"
            }
            CompositionLocalProvider(LocalConfiguration provides config.value) {
                DawTheme { DawApp(vm) }
            }
        }
    }
}
