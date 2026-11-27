package com.tvcanais.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class Canal(
    val nome: String,
    val url: String,
    val isWebSite: Boolean = false,
    val webOriginalUrl: String? = null
)

class MainActivity : ComponentActivity() {
    var isInPipMode by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TelaPrincipalTV(activity = this, isInPip = isInPipMode)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    fun entrarModoPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    fun alterarTelaCheia(ativar: Boolean) {
        requestedOrientation = if (ativar) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (ativar) {
                controller?.hide(WindowInsets.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller?.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (ativar) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }
}

@Composable
fun TelaPrincipalTV(activity: MainActivity, isInPip: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var linkDigitado by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }
    var telaCheia by remember { mutableStateOf(false) }
    var mostrarSiteCompleto by remember { mutableStateOf(false) }
    var mensagemStatus by remember { mutableStateOf<String?>(null) }

    var listaCanais by remember {
        mutableStateOf(
            carregarCanaisSalvos(context).ifEmpty {
                listOf(
                    Canal("Globoplay (Login / Ao Vivo)", "https://globoplay.globo.com/agora-na-tv/", isWebSite = true),
                    Canal("SBT Ao Vivo", "https://www.sbt.com.br/ao-vivo", isWebSite = true),
                    Canal("Band Ao Vivo", "https://www.band.uol.com.br/ao-vivo", isWebSite = true)
                )
            }
        )
    }

    var canalAtual by remember { mutableStateOf<Canal?>(null) }

    // Sai da tela cheia ao apertar o botão Voltar do celular
    BackHandler(enabled = telaCheia) {
        telaCheia = false
        activity.alterarTelaCheia(false)
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val atual = canalAtual
                    // Se o link capturado falhar no player nativo, volta para o player Web isolado
                    if (atual != null && atual.webOriginalUrl != null) {
                        canalAtual = atual.copy(
                            url = atual.webOriginalUrl,
                            isWebSite = true,
                            webOriginalUrl = null
                        )
                    } else {
                        mensagemStatus = "Erro ao abrir stream. Verifique se o canal está online."
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(canalAtual) {
        val canal = canalAtual
        mensagemStatus = null
        mostrarSiteCompleto = false
        if (canal != null && !canal.isWebSite) {
            exoPlayer.setMediaItem(MediaItem.fromUri(canal.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.pause()
        }
    }

    val playerExpandido = isInPip || telaCheia

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12))
    ) {
        // 1. BLOCO FIXO DO PICTURE PLAYER NO TOPO
        Box(
            modifier = if (playerExpandido) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            },
            contentAlignment = Alignment.Center
        ) {
            val atual = canalAtual
            if (atual == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "TV Picture Player",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Cole um link ou toque em 'Canais Brasil' abaixo",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else if (atual.isWebSite) {
                var jaCapturouStream by remember(atual.url) { mutableStateOf(false) }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.mediaPlaybackRequiresUserGesture = false

                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.grant(request.resources)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                // Tenta capturar automaticamente vídeo .m3u8/.mpd dentro da página
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    val ehStreamReal = (reqUrl.contains(".m3u8", ignoreCase = true) ||
                                            reqUrl.contains(".mpd", ignoreCase = true)) &&
                                            !reqUrl.contains("doubleclick", ignoreCase = true) &&
                                            !reqUrl.contains("googlesyndication", ignoreCase = true)

                                    if (ehStreamReal && !jaCapturouStream && !mostrarSiteCompleto) {
                                        jaCapturouStream = true
                                        view?.post {
                                            canalAtual = atual.copy(
                                                url = reqUrl,
                                                isWebSite = false,
                                                webOriginalUrl = atual.url
                                            )
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (!mostrarSiteCompleto) {
                                        // Isola apenas o vídeo da página para não parecer um site reduzido
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                function focarVideo() {
                                                    var v = document.querySelector('video');
                                                    if (v) {
                                                        v.style.position = 'fixed';
                                                        v.style.top = '0';
                                                        v.style.left = '0';
                                                        v.style.width = '100vw';
                                                        v.style.height = '100vh';
                                                        v.style.zIndex = '2147483647';
                                                        v.style.background = '#000';
                                                        v.style.objectFit = 'contain';
                                                        v.controls = true;
                                                        document.body.style.overflow = 'hidden';
                                                    }
                                                }
                                                focarVideo();
                                                setInterval(focarVideo, 1500);
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }
                            }
                            loadUrl(atual.url)
                        }
                    },
                    update = { webView ->
                        if (webView.url != atual.url) {
                            webView.loadUrl(atual.url)
                        }
                    }
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    }
                )
            }

            // Botões flutuantes discretos no canto superior direito do player (ocultos no PiP)
            if (atual != null && !isInPip) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (atual.isWebSite) {
                        BotaoOverlayPlayer(
                            texto = if (mostrarSiteCompleto) "Focar Vídeo" else "Site / Login"
                        ) {
                            mostrarSiteCompleto = !mostrarSiteCompleto
                            canalAtual = atual.copy()
                        }
                    }
                    BotaoOverlayPlayer(texto = "PiP") {
                        activity.entrarModoPip()
                    }
                    BotaoOverlayPlayer(texto = if (telaCheia) "Sair" else "Tela Cheia") {
                        val novoEstado = !telaCheia
                        telaCheia = novoEstado
                        activity.alterarTelaCheia(novoEstado)
                    }
                }
            }
        }

        // Tudo abaixo do player desaparece automaticamente quando entra em PiP ou Tela Cheia!
        if (!playerExpandido) {
            if (mensagemStatus != null) {
                Text(
                    text = mensagemStatus!!,
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // 2. BARRA DE ADICIONAR LINK
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = linkDigitado,
                    onValueChange = { linkDigitado = it },
                    placeholder = { Text("Cole link .m3u, .m3u8 ou site", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !carregando && linkDigitado.isNotBlank(),
                    onClick = {
                        val urlLimpa = linkDigitado.trim()
                        carregando = true
                        mensagemStatus = null
                        scope.launch {
                            val novosCanais = processarLink(urlLimpa)
                            val atualizada = novosCanais + listaCanais
                            listaCanais = atualizada
                            salvarCanais(context, atualizada)
                            if (novosCanais.isNotEmpty()) {
                                canalAtual = novosCanais.first()
                            }
                            linkDigitado = ""
                            carregando = false
                        }
                    }
                ) {
                    Text(if (carregando) "..." else "Add")
                }
            }

            // 3. BOTÃO RÁPIDO PARA CARREGAR CANAIS GRATUITOS DO BRASIL (PLAYER NATIVO PURO)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !carregando,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        carregando = true
                        mensagemStatus = "Baixando lista de canais abertos do Brasil..."
                        scope.launch {
                            val canaisBr = processarLink("https://iptv-org.github.io/iptv/countries/br.m3u")
                            if (canaisBr.size > 1) {
                                val atualizada = canaisBr + listaCanais
                                listaCanais = atualizada
                                salvarCanais(context, atualizada)
                                canalAtual = canaisBr.first()
                                mensagemStatus = "${canaisBr.size} canais carregados!"
                            } else {
                                mensagemStatus = "Não foi possível carregar a lista agora."
                            }
                            carregando = false
                        }
                    }
                ) {
                    Text("📺 Canais Brasil (Grátis)", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        listaCanais = emptyList()
                        canalAtual = null
                        salvarCanais(context, emptyList())
                    }
                ) {
                    Text("Limpar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF262630))

            // 4. LISTA ROLÁVEL ABAIXO DO PLAYER (SÓ A LISTA ROLA)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listaCanais) { canal ->
                    val estaTocando = canalAtual?.url == canal.url ||
                            (canalAtual?.webOriginalUrl != null && canalAtual?.webOriginalUrl == canal.url)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (estaTocando) Color(0xFF1F2A38) else Color(0xFF191920))
                            .clickable { canalAtual = canal }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Miniatura estilo bloco de player
                        Box(
                            modifier = Modifier
                                .size(width = 68.dp, height = 42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (estaTocando) Color(0xFF2E7D32) else Color(0xFF2A2A35)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (estaTocando) "▶ AO VIVO" else "▶ PLAY",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = canal.nome,
                                color = if (estaTocando) Color(0xFF66BB6A) else Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (canal.isWebSite) "Player Web Isolado" else "Player Nativo (HD)",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = "✕",
                            color = Color.Gray,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clickable {
                                    val novaLista = listaCanais.filterNot { it == canal }
                                    listaCanais = novaLista
                                    salvarCanais(context, novaLista)
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BotaoOverlayPlayer(texto: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xAA000000))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = texto, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

fun salvarCanais(context: Context, canais: List<Canal>) {
    val arr = JSONArray()
    canais.forEach { c ->
        val obj = JSONObject()
        obj.put("nome", c.nome)
        obj.put("url", c.url)
        obj.put("isWebSite", c.isWebSite)
        arr.put(obj)
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("canais", arr.toString())
        .apply()
}

fun carregarCanaisSalvos(context: Context): List<Canal> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .getString("canais", null) ?: return emptyList()
    return try {
        val list = mutableListOf<Canal>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Canal(
                    nome = obj.optString("nome", "Canal"),
                    url = obj.optString("url", ""),
                    isWebSite = obj.optBoolean("isWebSite", false)
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun processarLink(url: String): List<Canal> = withContext(Dispatchers.IO) {
    try {
        val conexao = URL(url).openConnection().apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val conteudo = conexao.getInputStream().bufferedReader().use { it.readText() }

        val ehListaDeCanais = conteudo.contains("#EXTM3U") &&
                conteudo.contains("#EXTINF") &&
                !conteudo.contains("#EXT-X-STREAM-INF") &&
                !conteudo.contains("#EXT-X-TARGETDURATION")

        if (ehListaDeCanais) {
            val canaisEncontrados = mutableListOf<Canal>()
            var nomeTemporario = "Canal"
            for (linha in conteudo.lines()) {
                val l = linha.trim()
                if (l.startsWith("#EXTINF")) {
                    nomeTemporario = l.substringAfterLast(",", "Canal").trim()
                } else if (l.startsWith("http")) {
                    canaisEncontrados.add(Canal(nome = nomeTemporario, url = l, isWebSite = false))
                }
            }
            if (canaisEncontrados.isNotEmpty()) return@withContext canaisEncontrados
        }

        val ehStream = conteudo.contains("#EXTM3U") ||
                url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".mpd", ignoreCase = true)

        return@withContext listOf(
            Canal(
                nome = url.removePrefix("https://").removePrefix("http://").take(35),
                url = url,
                isWebSite = !ehStream
            )
        )
    } catch (e: Exception) {
        val ehVideo = url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".mpd", ignoreCase = true)
        return@withContext listOf(
            Canal(
                nome = url.removePrefix("https://").removePrefix("http://").take(35),
                url = url,
                isWebSite = !ehVideo
            )
        )
    }
}
