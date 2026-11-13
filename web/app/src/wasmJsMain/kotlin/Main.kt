// Entry point of the web build: loads the saved files, fonts and sound engine, then shows the
// APK's own screens (DawTheme { DawApp(vm) }, as MainActivity does).
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
    val notes = vm.selectedChannel?.let { vm.selectedPattern.notesFor(it.id).size } ?: 0
    val desk = com.mobiledaw.ui.Desktop
    val extra = "\"tab\":\"${vm.tab}\",\"notes\":$notes,\"sel\":${vm.pianoSelection.size},\"barDp\":${vm.playlistBarDp},\"clips\":${p.clips.size}," +
        "\"typing\":\"${desk.typing}\",\"help\":${desk.shortcutsOpen},\"desktop\":${desk.active}"
    return "{\"name\":\"${p.name}\",\"channels\":${p.channels.size},\"playing\":${vm.playing},\"pos\":${com.mobiledaw.audio.NativeEngine.positionTicks()},$extra${if (probeText.isEmpty()) "" else ",$probeText"}}"
}

private external fun listenDesktopInput(
    onKey: (String, Boolean, Boolean, Boolean, Boolean, Boolean) -> Boolean,
    onBlur: () -> Unit,
    onMouse: () -> Unit,
    onRight: (Int, Float, Float) -> Unit,
)

private external fun registerProbe(probe: () -> String, togglePlay: () -> Unit, addBeat: () -> Unit)

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
        // Fallbacks for symbols Roboto lacks (Android finds these in its system fonts).
        val symbolFonts = listOf("Symbols-Math.ttf", "Symbols-2.ttf").mapNotNull { name ->
            fontBytes(name)?.toKotlin()?.let { FontFamily(Font(name, it, FontWeight.Normal)) }
        }
        WebFonts.regular = regular
        WebFonts.bold = bold
        val app = WebApp.context
        ThemeStore.load(app)
        val vm = DawViewModel(app)
        probeVm = vm
        registerProbe(::dawProbe, { vm.togglePlay() }) {
            // Tests only: new songs start empty, so put a kick on the beat to have something to hear.
            vm.addChannel(com.mobiledaw.model.Instrument.KICK)
            vm.selectedChannel?.let { kick -> listOf(0, 4, 8, 12).forEach { vm.toggleStep(kick, it) } }
        }
        // PC / laptop: keyboard shortcuts, typing keyboard and mouse extras (see ui/DesktopKeys.kt).
        com.mobiledaw.ui.Wheel.reportsPixels = true
        listenDesktopInput(
            { code, down, ctrl, shift, alt, repeat -> com.mobiledaw.ui.Desktop.onKey(vm, code, down, ctrl, shift, alt, repeat) },
            { com.mobiledaw.ui.Desktop.releaseAll(vm) },
            { if (!com.mobiledaw.ui.Desktop.active) com.mobiledaw.ui.Desktop.active = true },
            { phase, x, y -> com.mobiledaw.ui.RightMouse.dispatch(phase, x, y) },
        )
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
            // Fonts must be in before the app's first frame: text laid out earlier keeps empty boxes.
            var fontsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                resolver.preload(fonts)
                for (f in symbolFonts) resolver.preload(f)
                fontsReady = true
                val plainLayout = measurer.measure("Mobile DAW", TextStyle(fontSize = 20.sp))
                val plain = plainLayout.size
                // Symbols drawn from the fallback fonts measure like the symbol font itself.
                val sym = "♪⇄⋯♭↻▾"
                val symDefault = measurer.measure(sym, TextStyle(fontSize = 20.sp)).size.width
                val symMath = symbolFonts.firstOrNull()?.let { measurer.measure(sym, TextStyle(fontSize = 20.sp, fontFamily = it)).size.width } ?: -1
                fun inkGap(style: TextStyle): String {
                    // Where the ink of "M" sits in its text box: space above vs below (Android: about equal).
                    val l = measurer.measure("M", style)
                    val w = l.size.width.coerceAtLeast(1)
                    val h = l.size.height.coerceAtLeast(1)
                    val img = androidx.compose.ui.graphics.ImageBitmap(w, h)
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                        androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
                        androidx.compose.ui.graphics.Canvas(img), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
                    ) { drawText(l, color = androidx.compose.ui.graphics.Color.Black) }
                    val px = IntArray(w * h)
                    img.readPixels(px)
                    var top = -1
                    var bottom = -1
                    for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 128) { if (top < 0) top = y; bottom = y }
                    return "[${top},${h - 1 - bottom}]"
                }
                val gapDefault = inkGap(TextStyle(fontSize = 40.sp))
                val gapRoboto = inkGap(TextStyle(fontSize = 40.sp, fontFamily = fonts))
                probeText = "\"gapDefault\":$gapDefault,\"gapRoboto\":$gapRoboto,\"textW\":${plain.width},\"textH\":${plain.height},\"baseline\":${plainLayout.firstBaseline},\"symW\":$symDefault,\"symFontW\":$symMath"
            }
            if (fontsReady) {
                CompositionLocalProvider(LocalConfiguration provides config.value) {
                    DawTheme { DawApp(vm) }
                }
            }
        }
    }
}
