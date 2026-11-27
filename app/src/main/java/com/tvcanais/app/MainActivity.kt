package com.tvcanais.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.Xml
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

enum class AbaCategoria(val titulo: String, val icone: String) {
    INICIO("Início", "🔍"),
    GRADE("Grade TV", "📅"),
    YOUTUBE("YouTube", "▶️"),
    FAVORITOS("Favoritos", "⭐"),
    TV("Canais", "📺"),
    FILMES("Filmes", "🎬"),
    SERIES("Séries", "🎞️"),
    ANIMES("Animes", "⛩️"),
    NOVELAS("Novelas", "🌹")
}

data class Canal(
    val id: String = UUID.randomUUID().toString(),
    val nome: String,
    val url: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val isWebSite: Boolean = false,
    val webOriginalUrl: String? = null,
    val youtubeVideoId: String? = null,
    val categoria: AbaCategoria = AbaCategoria.TV,
    val favorito: Boolean = false
)

data class CanalYouTube(
    val channelId: String,
    val nome: String,
    val url: String,
    val pasta: String = "Geral",
    val favorito: Boolean = false
)

data class VideoYouTube(
    val videoId: String,
    val titulo: String,
    val publicado: String,
    val thumbUrl: String
)

data class ProgramaEpg(
    val channelId: String,
    val titulo: String,
    val inicioMs: Long,
    val fimMs: Long
)

data class CanalEpgInfo(
    val id: String,
    val nome: String,
    val logoUrl: String? = null
)

data class EpgDados(
    val canais: List<CanalEpgInfo> = emptyList(),
    val programasPorCanal: Map<String, List<ProgramaEpg>> = emptyMap()
)

val pastasPocketTube = listOf(
    "Todos", "Favoritos", "Geral", "Entretenimento",
    "Tecnologia", "Filmes & Séries", "Animes", "Games", "Notícias", "Música"
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

    var abaAtual by remember { mutableStateOf(AbaCategoria.INICIO) }
    var textoPesquisa by remember { mutableStateOf("") }
    var linkDigitado by remember { mutableStateOf("") }

    // Estados de EPG (Grade TV)
    var linkEpg by remember { mutableStateOf("https://epg.lat/files/br.xml.gz") }
    var epgDados by remember { mutableStateOf(EpgDados()) }
    var carregandoEpg by remember { mutableStateOf(false) }

    // Estados de PocketTube (CSV YouTube)
    var canaisYouTube by remember { mutableStateOf(carregarYouTubeSalvos(context)) }
    var pastaYtAtual by remember { mutableStateOf("Todos") }
    var canalYtSelecionado by remember { mutableStateOf<CanalYouTube?>(null) }
    var videosYtAtual by remember { mutableStateOf<List<VideoYouTube>>(emptyList()) }
    var videoYtSelecionadoIdx by remember { mutableStateOf(0) }
    var canalYtParaMover by remember { mutableStateOf<CanalYouTube?>(null) }

    // Estado de TV / Séries / Filmes / Animes / Novelas
    var canalParaMover by remember { mutableStateOf<Canal?>(null) }
    var carregando by remember { mutableStateOf(false) }
    var telaCheia by remember { mutableStateOf(false) }
    var mostrarSiteCompleto by remember { mutableStateOf(false) }
    var mensagemStatus by remember { mutableStateOf<String?>(null) }

    var listaCanais by remember {
        mutableStateOf(
            carregarCanaisSalvos(context).ifEmpty {
                listOf(
                    Canal(nome = "Globoplay (Ao Vivo)", url = "https://globoplay.globo.com/agora-na-tv/", isWebSite = true, categoria = AbaCategoria.TV),
                    Canal(nome = "SBT Ao Vivo (Site)", url = "https://www.sbt.com.br/ao-vivo", isWebSite = true, categoria = AbaCategoria.TV),
                    Canal(nome = "Band Ao Vivo (Site)", url = "https://www.band.uol.com.br/ao-vivo", isWebSite = true, categoria = AbaCategoria.TV)
                )
            }
        )
    }

    var canalAtual by remember { mutableStateOf<Canal?>(null) }

    fun atualizarCanais(novaLista: List<Canal>) {
        listaCanais = novaLista
        salvarCanais(context, novaLista)
    }

    fun atualizarCanaisYt(novaLista: List<CanalYouTube>) {
        canaisYouTube = novaLista
        salvarYouTube(context, novaLista)
    }

    // Seletor de arquivo CSV do PocketTube (Google Takeout subscriptions.csv)
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            carregando = true
            mensagemStatus = "Importando inscrições do CSV..."
            scope.launch {
                val importados = importarCsvYouTube(context, uri)
                if (importados.isNotEmpty()) {
                    val existentesIds = canaisYouTube.map { it.channelId }.toSet()
                    val novos = importados.filterNot { it.channelId in existentesIds }
                    atualizarCanaisYt(novos + canaisYouTube)
                    mensagemStatus = "${importados.size} canais do YouTube importados!"
                } else {
                    mensagemStatus = "Não achei canais nesse CSV. Use o subscriptions.csv do YouTube."
                }
                carregando = false
            }
        }
    }

    fun adicionarLinkDireto(urlRaw: String) {
        val urlLimpa = urlRaw.trim()
        if (urlLimpa.isBlank() || carregando) return
        carregando = true
        mensagemStatus = "Carregando link..."
        scope.launch {
            val novos = processarLink(urlLimpa)
            atualizarCanais(novos + listaCanais)
            if (novos.isNotEmpty()) {
                canalAtual = novos.first()
                canalYtSelecionado = null
                videosYtAtual = emptyList()
                mensagemStatus = if (novos.size > 1) "${novos.size} canais adicionados!" else "Link adicionado!"
            } else {
                mensagemStatus = "Nenhum canal encontrado nesse link."
            }
            linkDigitado = ""
            textoPesquisa = ""
            carregando = false
        }
    }

    fun abrirCanalYouTube(yt: CanalYouTube) {
        canalYtSelecionado = yt
        carregando = true
        mensagemStatus = "Carregando vídeos de ${yt.nome}..."
        scope.launch {
            val videos = buscarVideosYouTubeRss(yt.channelId)
            videosYtAtual = videos
            if (videos.isNotEmpty()) {
                videoYtSelecionadoIdx = 0
                val v = videos.first()
                canalAtual = Canal(
                    id = "yt_${v.videoId}",
                    nome = "${yt.nome}: ${v.titulo}",
                    url = "https://www.youtube.com/embed/${v.videoId}?autoplay=1&playsinline=1&rel=0",
                    logoUrl = v.thumbUrl,
                    isWebSite = true,
                    youtubeVideoId = v.videoId,
                    categoria = AbaCategoria.YOUTUBE
                )
                mensagemStatus = null
            } else {
                mensagemStatus = "Não foi possível carregar os vídeos deste canal."
            }
            carregando = false
        }
    }

    fun carregarGradeBrasil(urlEpg: String) {
        carregandoEpg = true
        mensagemStatus = "Baixando grade de programação (TV Map)..."
        scope.launch {
            val dados = baixarGradeEpg(urlEpg)
            if (dados.canais.isNotEmpty()) {
                epgDados = dados
                mensagemStatus = "Grade carregada: ${dados.canais.size} canais!"
            } else if (urlEpg.contains("epg.lat")) {
                val reserva = baixarGradeEpg("https://epg.pw/xmltv/epg_BR.xml.gz")
                if (reserva.canais.isNotEmpty()) {
                    epgDados = reserva
                    mensagemStatus = "Grade carregada: ${reserva.canais.size} canais!"
                } else {
                    mensagemStatus = "Não foi possível baixar a grade agora."
                }
            } else {
                mensagemStatus = "Não foi possível ler esse arquivo XMLTV."
            }
            carregandoEpg = false
        }
    }

    BackHandler(enabled = telaCheia) {
        telaCheia = false
        activity.alterarTelaCheia(false)
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val atual = canalAtual
                    if (atual != null && atual.webOriginalUrl != null) {
                        canalAtual = atual.copy(
                            url = atual.webOriginalUrl,
                            isWebSite = true,
                            webOriginalUrl = null
                        )
                    } else {
                        mensagemStatus = "Canal offline ou formato incompatível."
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
        mostrarSiteCompleto = false
        if (canal != null && !canal.isWebSite) {
            exoPlayer.setMediaItem(MediaItem.fromUri(canal.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.pause()
        }
    }

    val agoraMs = System.currentTimeMillis()

    val canaisFiltrados = remember(listaCanais, abaAtual, textoPesquisa) {
        val porAba = when (abaAtual) {
            AbaCategoria.INICIO -> listaCanais
            AbaCategoria.FAVORITOS -> listaCanais.filter { it.favorito }
            else -> listaCanais.filter { it.categoria == abaAtual }
        }
        val busca = textoPesquisa.trim()
        if (busca.isEmpty()) porAba
        else porAba.filter { it.nome.contains(busca, ignoreCase = true) }
    }

    val canaisYtFiltrados = remember(canaisYouTube, pastaYtAtual, textoPesquisa) {
        val porPasta = when (pastaYtAtual) {
            "Todos" -> canaisYouTube
            "Favoritos" -> canaisYouTube.filter { it.favorito }
            else -> canaisYouTube.filter { it.pasta == pastaYtAtual }
        }
        val busca = textoPesquisa.trim()
        if (busca.isEmpty()) porPasta
        else porPasta.filter { it.nome.contains(busca, ignoreCase = true) }
    }

    val canaisEpgFiltrados = remember(epgDados, textoPesquisa) {
        val busca = textoPesquisa.trim()
        if (busca.isEmpty()) epgDados.canais
        else epgDados.canais.filter { c ->
            val progs = epgDados.programasPorCanal[c.id].orEmpty()
            c.nome.contains(busca, ignoreCase = true) ||
                    progs.any { it.titulo.contains(busca, ignoreCase = true) }
        }
    }

    val playerExpandido = isInPip || telaCheia

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12))
            .then(
                if (playerExpandido) Modifier
                else Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
            )
    ) {
        // 1. BLOCO FIXO DO PLAYER NO TOPO (SEM VER ADS / SEM OBTER VIP)
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
                        text = "Canais, Grade TV Map, PocketTube CSV e Séries",
                        color = Color.Gray,
                        fontSize = 12.sp
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
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (atual.youtubeVideoId != null) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
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
                                    if (!mostrarSiteCompleto && atual.youtubeVideoId == null) {
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
                            if (atual.youtubeVideoId != null) {
                                loadUrl(atual.url, mapOf("Referer" to "https://www.youtube.com/"))
                            } else {
                                loadUrl(atual.url)
                            }
                        }
                    },
                    update = { webView ->
                        if (webView.url != atual.url) {
                            if (atual.youtubeVideoId != null) {
                                webView.loadUrl(atual.url, mapOf("Referer" to "https://www.youtube.com/"))
                            } else {
                                webView.loadUrl(atual.url)
                            }
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

            if (telaCheia && !isInPip) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    BotaoAcaoPlayer(texto = "✕ Sair") {
                        telaCheia = false
                        activity.alterarTelaCheia(false)
                    }
                }
            }
        }

        if (!playerExpandido) {
            // 2. BARRA LIMPA DO PLAYER (ESTILO SEEKEE: TÍTULO + FAVORITO + PIP + TELA CHEIA, ZERO ADS/VIP)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16161E))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = canalAtual?.nome ?: "Nenhum canal tocando",
                    color = if (canalAtual != null) Color(0xFF66BB6A) else Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                if (canalAtual != null) {
                    if (canalAtual!!.youtubeVideoId != null) {
                        BotaoAcaoPlayer(texto = "YT App") {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/watch?v=${canalAtual!!.youtubeVideoId}")
                            )
                            context.startActivity(intent)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (canalAtual!!.isWebSite) {
                        BotaoAcaoPlayer(
                            texto = if (mostrarSiteCompleto) "Vídeo" else "Site"
                        ) {
                            mostrarSiteCompleto = !mostrarSiteCompleto
                            canalAtual = canalAtual!!.copy()
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    BotaoAcaoPlayer(texto = "PiP") {
                        activity.entrarModoPip()
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    BotaoAcaoPlayer(texto = "Tela Cheia") {
                        telaCheia = true
                        activity.alterarTelaCheia(true)
                    }
                }
            }

            // FAIXA DE EPISÓDIOS NUMERADOS ESTILO SEEKEE (PARA CANAIS DO YOUTUBE / VÍDEOS)
            if (canalYtSelecionado != null && videosYtAtual.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF13131A))
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Episódios · ${canalYtSelecionado!!.nome} (${videosYtAtual.size})",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Fechar ✕",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable {
                                    canalYtSelecionado = null
                                    videosYtAtual = emptyList()
                                }
                                .padding(4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(videosYtAtual) { idx, vid ->
                            val ativo = idx == videoYtSelecionadoIdx
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (ativo) Color(0xFF2962FF) else Color(0xFF242430))
                                    .clickable {
                                        videoYtSelecionadoIdx = idx
                                        canalAtual = Canal(
                                            id = "yt_${vid.videoId}",
                                            nome = "${canalYtSelecionado!!.nome}: ${vid.titulo}",
                                            url = "https://www.youtube.com/embed/${vid.videoId}?autoplay=1&playsinline=1&rel=0",
                                            logoUrl = vid.thumbUrl,
                                            isWebSite = true,
                                            youtubeVideoId = vid.videoId,
                                            categoria = AbaCategoria.YOUTUBE
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            // 3. ABAS PRINCIPAIS ESTILO SEEKEE
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121218))
                    .padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AbaCategoria.entries) { aba ->
                    val selecionada = aba == abaAtual
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selecionada) Color(0xFF2962FF) else Color(0xFF22222C))
                            .clickable { abaAtual = aba }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${aba.icone} ${aba.titulo}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (selecionada) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 4. PESQUISA + CONTROLES ESPECÍFICOS DE CADA ABA
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CampoTextoCompacto(
                        valor = textoPesquisa,
                        aoMudar = { textoPesquisa = it },
                        dica = when (abaAtual) {
                            AbaCategoria.GRADE -> "🔍 Pesquisar canal ou programa na grade..."
                            AbaCategoria.YOUTUBE -> "🔍 Pesquisar canal do YouTube..."
                            else -> "🔍 Pesquisar em ${abaAtual.titulo}..."
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (textoPesquisa.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        BotaoAcaoPlayer(texto = "✕") { textoPesquisa = "" }
                    }
                }

                if (abaAtual == AbaCategoria.INICIO && textoPesquisa.trim().startsWith("http", ignoreCase = true)) {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        onClick = { adicionarLinkDireto(textoPesquisa) }
                    ) {
                        Text("➕ Adicionar este link na lista", fontSize = 12.sp)
                    }
                }

                when (abaAtual) {
                    AbaCategoria.INICIO -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = !carregando,
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                onClick = {
                                    carregando = true
                                    mensagemStatus = "Baixando canais abertos do Brasil..."
                                    scope.launch {
                                        val canaisBr = processarLink("https://iptv-org.github.io/iptv/countries/br.m3u")
                                        if (canaisBr.size > 1) {
                                            atualizarCanais(canaisBr + listaCanais)
                                            canalAtual = canaisBr.first()
                                            textoPesquisa = ""
                                            mensagemStatus = "${canaisBr.size} canais carregados!"
                                        } else {
                                            mensagemStatus = "Erro ao baixar lista agora."
                                        }
                                        carregando = false
                                    }
                                }
                            ) {
                                Text(
                                    text = if (carregando) "Carregando..." else "📺 Canais Brasil (Grátis)",
                                    fontSize = 12.sp
                                )
                            }

                            OutlinedButton(
                                modifier = Modifier.height(38.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                onClick = {
                                    atualizarCanais(emptyList())
                                    canalAtual = null
                                    textoPesquisa = ""
                                    mensagemStatus = "Lista limpa."
                                }
                            ) {
                                Text("Limpar", fontSize = 12.sp)
                            }
                        }

                        // CAMPO DE LINK DIRETO LOGO ABAIXO DE CANAIS BRASIL (GRÁTIS)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CampoTextoCompacto(
                                valor = linkDigitado,
                                aoMudar = { linkDigitado = it },
                                dica = "🔗 Cole link .m3u, .m3u8 ou site aqui",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                enabled = !carregando && linkDigitado.isNotBlank(),
                                modifier = Modifier.height(42.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                onClick = { adicionarLinkDireto(linkDigitado) }
                            ) {
                                Text(if (carregando) "..." else "Add", fontSize = 13.sp)
                            }
                        }
                    }

                    AbaCategoria.GRADE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = !carregandoEpg,
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                onClick = { carregarGradeBrasil(linkEpg) }
                            ) {
                                Text(
                                    text = if (carregandoEpg) "Atualizando..." else "📅 Atualizar Grade TV BR",
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CampoTextoCompacto(
                                valor = linkEpg,
                                aoMudar = { linkEpg = it },
                                dica = "Link XMLTV (.xml ou .xml.gz)",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = !carregandoEpg && linkEpg.isNotBlank(),
                                modifier = Modifier.height(42.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                onClick = { carregarGradeBrasil(linkEpg.trim()) }
                            ) {
                                Text("Abrir", fontSize = 12.sp)
                            }
                        }
                    }

                    AbaCategoria.YOUTUBE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = !carregando,
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                onClick = {
                                    csvPickerLauncher.launch(
                                        arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*")
                                    )
                                }
                            ) {
                                Text("📂 Importar CSV (Inscrições)", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                modifier = Modifier.height(38.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                onClick = {
                                    atualizarCanaisYt(emptyList())
                                    canalYtSelecionado = null
                                    videosYtAtual = emptyList()
                                    mensagemStatus = "Canais do YouTube limpos."
                                }
                            ) {
                                Text("Limpar", fontSize = 12.sp)
                            }
                        }

                        // Pastas estilo PocketTube
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(pastasPocketTube) { pasta ->
                                val ativa = pasta == pastaYtAtual
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (ativa) Color(0xFF2E7D32) else Color(0xFF20202A))
                                        .clickable { pastaYtAtual = pasta }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = pasta,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (ativa) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }

            if (mensagemStatus != null) {
                Text(
                    text = mensagemStatus!!,
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            HorizontalDivider(color = Color(0xFF262630))

            // 5. CONTEÚDO ROLÁVEL DE ACORDO COM A ABA
            when (abaAtual) {
                AbaCategoria.GRADE -> {
                    if (canaisEpgFiltrados.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Toque em '📅 Atualizar Grade TV BR' acima\npara carregar a programação ao vivo tipo TV Map.",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(canaisEpgFiltrados, key = { it.id }) { epgCanal ->
                                val programas = epgDados.programasPorCanal[epgCanal.id].orEmpty()
                                val progAtual = programas.firstOrNull { agoraMs in it.inicioMs..it.fimMs }
                                val proximoProg = programas.firstOrNull { it.inicioMs > agoraMs }
                                val canalCorrespondente = encontrarCanalNaLista(listaCanais, epgCanal.id, epgCanal.nome)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF191920))
                                        .clickable {
                                            if (canalCorrespondente != null) {
                                                canalAtual = canalCorrespondente
                                            } else {
                                                mensagemStatus = "Canal '${epgCanal.nome}' não está na sua lista de reprodução."
                                            }
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!epgCanal.logoUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = epgCanal.logoUrl,
                                                contentDescription = epgCanal.nome,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .size(width = 44.dp, height = 32.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF252530))
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }
                                        Text(
                                            text = epgCanal.nome,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (canalCorrespondente != null) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF2E7D32))
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text("▶ ASSISTIR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (progAtual != null) {
                                        val duracao = (progAtual.fimMs - progAtual.inicioMs).coerceAtLeast(1L)
                                        val progresso = ((agoraMs - progAtual.inicioMs).toFloat() / duracao).coerceIn(0f, 1f)
                                        Text(
                                            text = "AGORA (${formatarHora(progAtual.inicioMs)} - ${formatarHora(progAtual.fimMs)}): ${progAtual.titulo}",
                                            color = Color(0xFF66BB6A),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { progresso },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = Color(0xFF66BB6A),
                                            trackColor = Color(0xFF2C2C38)
                                        )
                                    } else {
                                        Text(
                                            text = "Sem informação do programa atual",
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                    }

                                    if (proximoProg != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "A SEGUIR (${formatarHora(proximoProg.inicioMs)}): ${proximoProg.titulo}",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AbaCategoria.YOUTUBE -> {
                    if (canaisYtFiltrados.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Importe o arquivo 'subscriptions.csv' do seu YouTube\npara organizar seus canais estilo PocketTube!",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(canaisYtFiltrados, key = { _, c -> c.channelId }) { index, yt ->
                                val selecionado = canalYtSelecionado?.channelId == yt.channelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selecionado) Color(0xFF1F2A38) else Color(0xFF191920))
                                        .clickable { abrirCanalYouTube(yt) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 48.dp, height = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFC62828)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = yt.nome,
                                            color = if (selecionado) Color(0xFF66BB6A) else Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "YouTube · Pasta: ${yt.pasta}",
                                            color = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = if (yt.favorito) "⭐" else "☆",
                                        color = if (yt.favorito) Color(0xFFFFD54F) else Color.Gray,
                                        fontSize = 18.sp,
                                        modifier = Modifier
                                            .clickable {
                                                atualizarCanaisYt(
                                                    canaisYouTube.map {
                                                        if (it.channelId == yt.channelId) it.copy(favorito = !it.favorito) else it
                                                    }
                                                )
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    Text(
                                        text = "⇄",
                                        color = Color(0xFF64B5F6),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { canalYtParaMover = yt }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    Text(
                                        text = "✕",
                                        color = Color.Gray,
                                        fontSize = 15.sp,
                                        modifier = Modifier
                                            .clickable {
                                                atualizarCanaisYt(canaisYouTube.filterNot { it.channelId == yt.channelId })
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (canaisFiltrados.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (textoPesquisa.isNotBlank()) {
                                    "Nenhum canal encontrado para \"$textoPesquisa\""
                                } else {
                                    "Nenhum item em ${abaAtual.titulo}.\nCole um link acima ou toque em Canais Brasil."
                                },
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(canaisFiltrados, key = { _, c -> c.id }) { index, canal ->
                                val estaTocando = canalAtual?.id == canal.id
                                val progAgora = buscarProgramaAtual(epgDados, canal, agoraMs)

                                // Card estilo ranking Seekee (capa/logo + número + título + metadados)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (estaTocando) Color(0xFF1F2A38) else Color(0xFF191920))
                                        .clickable {
                                            canalAtual = canal
                                            canalYtSelecionado = null
                                            videosYtAtual = emptyList()
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 64.dp, height = 50.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (estaTocando) Color(0xFF2E7D32) else Color(0xFF252532)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!canal.logoUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = canal.logoUrl,
                                                contentDescription = canal.nome,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize().padding(4.dp)
                                            )
                                        } else {
                                            Text(
                                                text = if (estaTocando) "▶ VIVO" else "▶ PLAY",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // Badge numerada no canto superior esquerdo estilo Seekee
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .clip(RoundedCornerShape(bottomEnd = 6.dp))
                                                .background(if (index < 3) Color(0xFFE53935) else Color(0xAA000000))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = canal.nome,
                                            color = if (estaTocando) Color(0xFF66BB6A) else Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (progAgora != null) {
                                            Text(
                                                text = "Agora (${formatarHora(progAgora.inicioMs)}): ${progAgora.titulo}",
                                                color = Color(0xFF81C784),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${canal.categoria.icone} ${canal.categoria.titulo} · ${if (canal.isWebSite) "Web" else "HD"}",
                                            color = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Text(
                                        text = if (canal.favorito) "⭐" else "☆",
                                        color = if (canal.favorito) Color(0xFFFFD54F) else Color.Gray,
                                        fontSize = 18.sp,
                                        modifier = Modifier
                                            .clickable {
                                                atualizarCanais(
                                                    listaCanais.map {
                                                        if (it.id == canal.id) it.copy(favorito = !it.favorito) else it
                                                    }
                                                )
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )

                                    Text(
                                        text = "⇄",
                                        color = Color(0xFF64B5F6),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { canalParaMover = canal }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )

                                    Text(
                                        text = "✕",
                                        color = Color.Gray,
                                        fontSize = 15.sp,
                                        modifier = Modifier
                                            .clickable {
                                                atualizarCanais(listaCanais.filterNot { it.id == canal.id })
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // POPUP: MOVER CANAL DE TV/SÉRIE/FILME/ANIME/NOVELA PARA OUTRA ABA
    val movendo = canalParaMover
    if (movendo != null) {
        val destinos = listOf(
            AbaCategoria.TV, AbaCategoria.FILMES, AbaCategoria.SERIES,
            AbaCategoria.ANIMES, AbaCategoria.NOVELAS
        )
        AlertDialog(
            onDismissRequest = { canalParaMover = null },
            title = { Text("Mover canal para:") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = movendo.nome,
                        color = Color(0xFF66BB6A),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    destinos.forEach { destino ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (movendo.categoria == destino) Color(0xFF2E7D32)
                                    else Color(0xFF262632)
                                )
                                .clickable {
                                    atualizarCanais(
                                        listaCanais.map {
                                            if (it.id == movendo.id) it.copy(categoria = destino) else it
                                        }
                                    )
                                    canalParaMover = null
                                }
                                .padding(12.dp)
                        ) {
                            Text(text = "${destino.icone}  ${destino.titulo}", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { canalParaMover = null }) { Text("Fechar") }
            }
        )
    }

    // POPUP: MOVER CANAL DO YOUTUBE PARA OUTRA PASTA (POCKETTUBE)
    val movendoYt = canalYtParaMover
    if (movendoYt != null) {
        val pastasDestino = pastasPocketTube.filter { it != "Todos" && it != "Favoritos" }
        AlertDialog(
            onDismissRequest = { canalYtParaMover = null },
            title = { Text("Mover para pasta (PocketTube):") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pastasDestino) { pasta ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (movendoYt.pasta == pasta) Color(0xFF2E7D32) else Color(0xFF262632))
                                .clickable {
                                    atualizarCanaisYt(
                                        canaisYouTube.map {
                                            if (it.channelId == movendoYt.channelId) it.copy(pasta = pasta) else it
                                        }
                                    )
                                    canalYtParaMover = null
                                }
                                .padding(12.dp)
                        ) {
                            Text(text = pasta, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { canalYtParaMover = null }) { Text("Fechar") }
            }
        )
    }
}

@Composable
fun CampoTextoCompacto(
    valor: String,
    aoMudar: (String) -> Unit,
    dica: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1C26))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (valor.isEmpty()) {
            Text(
                text = dica,
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFF66BB6A)),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun BotaoAcaoPlayer(texto: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2B2B38))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text = texto, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- PERSISTÊNCIA ----------

fun salvarCanais(context: Context, canais: List<Canal>) {
    val arr = JSONArray()
    canais.forEach { c ->
        val obj = JSONObject()
        obj.put("id", c.id)
        obj.put("nome", c.nome)
        obj.put("url", c.url)
        obj.put("logoUrl", c.logoUrl ?: "")
        obj.put("tvgId", c.tvgId ?: "")
        obj.put("isWebSite", c.isWebSite)
        obj.put("categoria", c.categoria.name)
        obj.put("favorito", c.favorito)
        arr.put(obj)
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .edit().putString("canais", arr.toString()).apply()
}

fun carregarCanaisSalvos(context: Context): List<Canal> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .getString("canais", null) ?: return emptyList()
    return try {
        val list = mutableListOf<Canal>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val catNome = obj.optString("categoria", AbaCategoria.TV.name)
            val cat = AbaCategoria.entries.find { it.name == catNome } ?: AbaCategoria.TV
            list.add(
                Canal(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    nome = obj.optString("nome", "Canal"),
                    url = obj.optString("url", ""),
                    logoUrl = obj.optString("logoUrl", "").ifBlank { null },
                    tvgId = obj.optString("tvgId", "").ifBlank { null },
                    isWebSite = obj.optBoolean("isWebSite", false),
                    categoria = cat,
                    favorito = obj.optBoolean("favorito", false)
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

fun salvarYouTube(context: Context, canais: List<CanalYouTube>) {
    val arr = JSONArray()
    canais.forEach { c ->
        val obj = JSONObject()
        obj.put("channelId", c.channelId)
        obj.put("nome", c.nome)
        obj.put("url", c.url)
        obj.put("pasta", c.pasta)
        obj.put("favorito", c.favorito)
        arr.put(obj)
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .edit().putString("yt_canais", arr.toString()).apply()
}

fun carregarYouTubeSalvos(context: Context): List<CanalYouTube> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        .getString("yt_canais", null) ?: return emptyList()
    return try {
        val list = mutableListOf<CanalYouTube>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                CanalYouTube(
                    channelId = obj.optString("channelId", ""),
                    nome = obj.optString("nome", "Canal YT"),
                    url = obj.optString("url", ""),
                    pasta = obj.optString("pasta", "Geral"),
                    favorito = obj.optBoolean("favorito", false)
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

// ---------- M3U + LINK PARSER ----------

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
            var logoTemporaria: String? = null
            var idTemporario: String? = null

            val regexLogo = Regex("""tvg-logo="([^"]*)"""")
            val regexId = Regex("""tvg-id="([^"]*)"""")

            for (linha in conteudo.lines()) {
                val l = linha.trim()
                if (l.startsWith("#EXTINF")) {
                    nomeTemporario = l.substringAfterLast(",", "Canal").trim()
                    logoTemporaria = regexLogo.find(l)?.groupValues?.getOrNull(1)?.ifBlank { null }
                    idTemporario = regexId.find(l)?.groupValues?.getOrNull(1)?.ifBlank { null }
                } else if (l.startsWith("http")) {
                    canaisEncontrados.add(
                        Canal(
                            nome = nomeTemporario,
                            url = l,
                            logoUrl = logoTemporaria,
                            tvgId = idTemporario,
                            isWebSite = false,
                            categoria = AbaCategoria.TV
                        )
                    )
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
                isWebSite = !ehStream,
                categoria = AbaCategoria.TV
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
                isWebSite = !ehVideo,
                categoria = AbaCategoria.TV
            )
        )
    }
}

// ---------- POCKETTUBE: CSV + YOUTUBE RSS ----------

suspend fun importarCsvYouTube(context: Context, uri: Uri): List<CanalYouTube> = withContext(Dispatchers.IO) {
    try {
        val texto = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@withContext emptyList()

        val resultado = mutableListOf<CanalYouTube>()
        for (linhaRaw in texto.lines()) {
            val linha = linhaRaw.trim().removePrefix("\uFEFF")
            if (linha.isBlank()) continue

            val colunas = dividirLinhaCsv(linha)
            if (colunas.size < 2) continue

            val col0 = colunas[0].trim()
            val col1 = colunas[1].trim()
            val col2 = if (colunas.size >= 3) colunas[2].trim() else ""

            // Ignora cabeçalho
            if (col0.contains("Channel", ignoreCase = true) || col0.contains("ID do canal", ignoreCase = true)) {
                continue
            }

            val id = when {
                col0.startsWith("UC") -> col0
                col1.contains("/channel/") -> col1.substringAfter("/channel/").substringBefore("/")
                else -> col0
            }
            val urlCanal = if (col1.startsWith("http")) col1 else "https://www.youtube.com/channel/$id"
            val nome = col2.ifBlank { col1.ifBlank { id } }

            if (id.isNotBlank()) {
                resultado.add(CanalYouTube(channelId = id, nome = nome, url = urlCanal))
            }
        }
        resultado
    } catch (e: Exception) {
        emptyList()
    }
}

fun dividirLinhaCsv(linha: String): List<String> {
    val campos = mutableListOf<String>()
    val atual = StringBuilder()
    var dentroAspas = false
    for (c in linha) {
        when {
            c == '"' -> dentroAspas = !dentroAspas
            c == ',' && !dentroAspas -> {
                campos.add(atual.toString())
                atual.clear()
            }
            else -> atual.append(c)
        }
    }
    campos.add(atual.toString())
    return campos
}

suspend fun buscarVideosYouTubeRss(channelId: String): List<VideoYouTube> = withContext(Dispatchers.IO) {
    try {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val conexao = URL(url).openConnection().apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val parser = Xml.newPullParser()
        parser.setInput(conexao.getInputStream(), "UTF-8")

        val videos = mutableListOf<VideoYouTube>()
        var dentroEntry = false
        var videoId = ""
        var titulo = ""
        var publicado = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "entry") {
                        dentroEntry = true
                        videoId = ""
                        titulo = ""
                        publicado = ""
                    } else if (dentroEntry) {
                        when (tag) {
                            "videoId" -> videoId = parser.nextText().trim()
                            "title" -> titulo = parser.nextText().trim()
                            "published" -> publicado = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry" && dentroEntry) {
                        if (videoId.isNotBlank()) {
                            videos.add(
                                VideoYouTube(
                                    videoId = videoId,
                                    titulo = titulo.ifBlank { "Vídeo" },
                                    publicado = publicado.take(10),
                                    thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                )
                            )
                        }
                        dentroEntry = false
                    }
                }
            }
            event = parser.next()
        }
        videos
    } catch (e: Exception) {
        emptyList()
    }
}

// ---------- GRADE DE PROGRAMAÇÃO TV MAP (EPG XMLTV) ----------

suspend fun baixarGradeEpg(url: String): EpgDados = withContext(Dispatchers.IO) {
    try {
        val conexao = URL(url).openConnection().apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val pb = PushbackInputStream(conexao.getInputStream(), 2)
        val sig = ByteArray(2)
        val len = pb.read(sig)
        if (len > 0) pb.unread(sig, 0, len)

        val stream: InputStream = if (len == 2 && sig[0] == 0x1f.toByte() && sig[1] == 0x8b.toByte()) {
            GZIPInputStream(pb)
        } else {
            pb
        }

        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

        val agora = System.currentTimeMillis()
        val janelaMin = agora - 30 * 60_000L
        val janelaMax = agora + 12 * 3600_000L

        val canaisMap = linkedMapOf<String, CanalEpgInfo>()
        val programasMap = mutableMapOf<String, MutableList<ProgramaEpg>>()

        var chIdAtual = ""
        var chNomeAtual = ""
        var chLogoAtual: String? = null

        var progChId = ""
        var progStart = 0L
        var progStop = 0L
        var progTitulo = ""

        var dentroChannel = false
        var dentroProg = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            dentroChannel = true
                            chIdAtual = parser.getAttributeValue(null, "id") ?: ""
                            chNomeAtual = chIdAtual
                            chLogoAtual = null
                        }
                        "display-name" -> if (dentroChannel) {
                            val t = parser.nextText().trim()
                            if (t.isNotBlank()) chNomeAtual = t
                        }
                        "icon" -> if (dentroChannel) {
                            chLogoAtual = parser.getAttributeValue(null, "src")
                        }
                        "programme" -> {
                            dentroProg = true
                            progChId = parser.getAttributeValue(null, "channel") ?: ""
                            progStart = parsearDataXmltv(parser.getAttributeValue(null, "start"))
                            progStop = parsearDataXmltv(parser.getAttributeValue(null, "stop"))
                            progTitulo = ""
                        }
                        "title" -> if (dentroProg) {
                            progTitulo = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            if (dentroChannel && chIdAtual.isNotBlank()) {
                                canaisMap[chIdAtual] = CanalEpgInfo(chIdAtual, chNomeAtual, chLogoAtual)
                            }
                            dentroChannel = false
                        }
                        "programme" -> {
                            if (dentroProg && progChId.isNotBlank() && progTitulo.isNotBlank()) {
                                if (progStop > janelaMin && progStart < janelaMax) {
                                    programasMap.getOrPut(progChId) { mutableListOf() }
                                        .add(ProgramaEpg(progChId, progTitulo, progStart, progStop))
                                }
                            }
                            dentroProg = false
                        }
                    }
                }
            }
            event = parser.next()
        }

        val programasOrdenados = programasMap.mapValues { (_, v) -> v.sortedBy { it.inicioMs } }
        EpgDados(canais = canaisMap.values.toList(), programasPorCanal = programasOrdenados)
    } catch (e: Exception) {
        EpgDados()
    }
}

fun parsearDataXmltv(valor: String?): Long {
    if (valor.isNullOrBlank()) return 0L
    return try {
        val limpo = valor.trim()
        if (limpo.contains("+") || limpo.contains("-")) {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(limpo)?.time ?: 0L
        } else {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(limpo.take(14))?.time ?: 0L
        }
    } catch (e: Exception) {
        0L
    }
}

fun formatarHora(ms: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}

fun normalizarNome(texto: String): String {
    val semParenteses = texto
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^]]*\\]"), "")
    val nfd = Normalizer.normalize(semParenteses, Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}

fun encontrarCanalNaLista(lista: List<Canal>, epgId: String, epgNome: String): Canal? {
    val idNorm = normalizarNome(epgId.substringBefore("."))
    val nomeNorm = normalizarNome(epgNome)
    return lista.firstOrNull { c ->
        val cTvg = c.tvgId?.let { normalizarNome(it.substringBefore(".")) }
        val cNome = normalizarNome(c.nome)
        (cTvg != null && (cTvg == normalizarNome(epgId) || cTvg == idNorm)) ||
                cNome == nomeNorm ||
                (nomeNorm.length >= 3 && cNome.contains(nomeNorm)) ||
                (cNome.length >= 3 && nomeNorm.contains(cNome))
    }
}

fun buscarProgramaAtual(epg: EpgDados, canal: Canal, agoraMs: Long): ProgramaEpg? {
    if (epg.canais.isEmpty()) return null
    val cTvg = canal.tvgId
    if (!cTvg.isNullOrBlank()) {
        val direto = epg.programasPorCanal[cTvg]?.firstOrNull { agoraMs in it.inicioMs..it.fimMs }
        if (direto != null) return direto
    }
    val cNomeNorm = normalizarNome(canal.nome)
    val epgCanal = epg.canais.firstOrNull {
        val eNorm = normalizarNome(it.nome)
        eNorm == cNomeNorm || (eNorm.length >= 3 && cNomeNorm.contains(eNorm))
    } ?: return null
    return epg.programasPorCanal[epgCanal.id]?.firstOrNull { agoraMs in it.inicioMs..it.fimMs }
}
