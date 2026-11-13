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
import kotlinx.coroutines.flow.first
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
private val appTextStyle = mutableStateOf<TextStyle?>(null)

/** For automated checks (web/app/tests): a few facts about the running app, as JSON. */
private fun dawProbe(): String {
    val vm = probeVm ?: return "{}"
    val p = vm.project
    return "{\"name\":\"${p.name}\",\"channels\":${p.channels.size},\"playing\":${vm.playing},\"pos\":${com.mobiledaw.audio.NativeEngine.positionTicks()}${if (probeText.isEmpty()) "" else ",$probeText"}}"
}

private external fun registerProbe(probe: () -> String, togglePlay: () -> Unit, addBeat: () -> Unit)
private external fun registerCloudProbe(probe: () -> String, signIn: () -> Unit, syncNow: () -> Unit, newProject: () -> Unit)

/** Tests only: what the Google Drive sync is doing, as JSON. */
private fun cloudProbe(): String {
    val vm = probeVm ?: return "{}"
    val c = vm.cloud
    val names = vm.savedProjects().joinToString(",") { "\"${it.replace("\"", "")}\"" }
    return "{\"status\":\"${c.status}\",\"signedIn\":${c.signedIn},\"email\":\"${c.email ?: ""}\",\"revision\":${c.revision},\"projects\":[$names],\"open\":\"${vm.project.name}\",\"channels\":${vm.project.channels.size},\"notes\":${vm.project.patterns.sumOf { p -> p.notes.values.sumOf { it.size } }},\"problem\":\"${c.problem ?: ""}\"}"
}

/** Android-like single-line text: extra line height split evenly above and below the glyphs. */
private fun webText(style: TextStyle): TextStyle = style.copy(
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)

/**
 * The APK's theme with Android's single-line text spacing. On the phone, a label's extra line
 * height (Material's 24sp body line under 12sp text) is trimmed off; in the browser it was kept
 * and put mostly above the text, so labels sat low in their chips and buttons.
 */
@androidx.compose.runtime.Composable
private fun WebTypography(content: @androidx.compose.runtime.Composable () -> Unit) {
    val t = androidx.compose.material3.MaterialTheme.typography
    val fixed = androidx.compose.material3.Typography(
        displayLarge = webText(t.displayLarge), displayMedium = webText(t.displayMedium), displaySmall = webText(t.displaySmall),
        headlineLarge = webText(t.headlineLarge), headlineMedium = webText(t.headlineMedium), headlineSmall = webText(t.headlineSmall),
        titleLarge = webText(t.titleLarge), titleMedium = webText(t.titleMedium), titleSmall = webText(t.titleSmall),
        bodyLarge = webText(t.bodyLarge), bodyMedium = webText(t.bodyMedium), bodySmall = webText(t.bodySmall),
        labelLarge = webText(t.labelLarge), labelMedium = webText(t.labelMedium), labelSmall = webText(t.labelSmall),
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.MaterialTheme.colorScheme,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
        typography = fixed,
    ) {
        androidx.compose.material3.ProvideTextStyle(fixed.bodyLarge) {
            appTextStyle.value = androidx.compose.material3.LocalTextStyle.current
            content()
        }
    }
}

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
        registerCloudProbe(::cloudProbe, { vm.cloud.signIn() }, { vm.cloud.syncSoon(0) }, { vm.newProject() })
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
                androidx.compose.runtime.snapshotFlow { appTextStyle.value }.first { it != null }
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
                // A chip label as the app writes it: 12sp text inside Material's 24sp body line height.
                val body = androidx.compose.material3.Typography().bodyLarge
                val gapLine = inkGap(body.merge(TextStyle(fontSize = 40.sp, lineHeight = 80.sp)))
                // ...and as the app's screens get it (their LocalTextStyle, see WebTypography).
                val gapLineFixed = inkGap(appTextStyle.value!!.merge(TextStyle(fontSize = 40.sp, lineHeight = 80.sp)))
                probeText = "\"gapLine\":$gapLine,\"gapLineFixed\":$gapLineFixed,\"gapDefault\":$gapDefault,\"gapRoboto\":$gapRoboto,\"textW\":${plain.width},\"textH\":${plain.height},\"baseline\":${plainLayout.firstBaseline},\"symW\":$symDefault,\"symFontW\":$symMath"
            }
            if (fontsReady) {
                CompositionLocalProvider(LocalConfiguration provides config.value) {
                    DawTheme { WebTypography { DawApp(vm) } }
                }
            }
        }
    }
}
