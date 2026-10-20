package io.github.iaguito22.oto.ui.menu

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.account.Session
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Entrar con tu cuenta de YouTube Music: la página de Google dentro de Oto. Al volver a music.youtube.com ya con
 * sesión se leen sus cookies y su configuración (como hace Metrolist) y la página se cierra.
 */
@Composable
fun AccountView(onBack: () -> Unit, onSignedIn: () -> Unit) {
    val c = Oto.colors
    val account = (LocalContext.current.applicationContext as OtoApp).account
    val session by account.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(web) { web = false }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = { if (web) web = false else onBack() })
            Text(if (web) "Entrar con Google" else "Tu cuenta", style = OtoType.title, color = c.text)
        }
        Spacer(Modifier.height(Space.sm))
        AnimatedContent(
            targetState = when { session != null -> 2; web -> 1; else -> 0 },
            transitionSpec = { fadeIn(tween(Motion.EFFECTS)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            label = "account",
        ) { step ->
            when (step) {
                2 -> session?.let { s -> SignedIn(s, onSignOut = { scope.launch { account.signOut(); onSignedIn() } }) }
                1 -> SignInPage(
                    modifier = Modifier.fillMaxSize().padding(bottom = Space.md).clip(RoundedCornerShape(Radius.lg)),
                    onSession = { s -> scope.launch { account.signIn(s); web = false; onSignedIn() } },
                    onFail = { error = it; web = false },
                )
                else -> Column {
                    Block(
                        "Entrar con YouTube Music",
                        "La portada y la radio saldrán de tus gustos, lo que oigas contará en tu historial de YouTube " +
                            "y lo que te guste en Oto también le gustará a YouTube Music. Oto solo guarda la sesión en el móvil.",
                    ) { Pill("Entrar con Google", { error = null; web = true }) }
                    error?.let { Text(it, style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(Space.md)) }
                }
            }
        }
    }
}

@Composable
private fun SignedIn(s: Session, onSignOut: () -> Unit) {
    val c = Oto.colors
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.field).padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(s.photo, 52.dp, 52.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(s.name.ifEmpty { "Tu cuenta" }, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                s.email?.let { Text(it, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Text(
            "Explorar y la radio salen de tus gustos. Lo que oyes cuenta en tu historial de YouTube y tus me gusta se guardan también allí.",
            style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(Space.md),
        )
        Box(Modifier.padding(horizontal = Space.sm)) { Pill("Cerrar sesión", onSignOut, filled = false) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInPage(modifier: Modifier, onSession: (Session) -> Unit, onFail: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    // Solo la web: los enlaces a apps (intent://) no se abren desde aquí.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        request.url.scheme != "http" && request.url.scheme != "https"

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (reading || url?.let(Uri::parse)?.host != "music.youtube.com") return
                        reading = true
                        scope.launch {
                            val s = read(view)
                            if (s != null) onSession(s) else {
                                reading = false
                                onFail("YouTube Music no terminó de abrir la sesión. Prueba otra vez.")
                            }
                        }
                    }
                }
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        },
    )
}

/** Las cookies (con SAPISID, la que firma las peticiones) y la configuración de la página; tarda un poco en estar. */
private suspend fun read(view: WebView): Session? {
    repeat(20) {
        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
        val config = view.eval(
            "(function(){var c=window.yt&&window.yt.config_;return c?JSON.stringify({v:c.VISITOR_DATA||'',d:c.DATASYNC_ID||'',u:String(c.SESSION_INDEX||0)}):''})()",
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        val visitor = config?.optString("v").orEmpty()
        if ("SAPISID" in cookie && visitor.isNotEmpty()) {
            return Session(
                cookie = cookie,
                visitorData = visitor,
                dataSyncId = config!!.optString("d").substringBefore("||"),
                authUser = config.optString("u").filter(Char::isDigit).ifEmpty { "0" },
            )
        }
        delay(500)
    }
    return null
}

private suspend fun WebView.eval(js: String): String? = suspendCoroutine { k ->
    evaluateJavascript(js) { r -> k.resume(runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()?.ifEmpty { null }) }
}
