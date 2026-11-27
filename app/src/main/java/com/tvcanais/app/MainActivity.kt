package com.tvcanais.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import kotlinx.coroutines.launch
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    var isInPipMode by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { TelaPrincipalApp(this, isInPipMode) }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    fun entrarModoPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    fun alterarTelaCheia(ativar: Boolean) {
        requestedOrientation = if (ativar) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                if (ativar) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (ativar) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}

fun montarHtmlIframeYoutube(videoId: String): String = """
    <!DOCTYPE html><html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <meta name="referrer" content="strict-origin-when-cross-origin">
    <style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}iframe{width:100%;height:100%;border:0;}</style>
    </head><body>
    <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=https://com.tvcanais.app"
    referrerpolicy="strict-origin-when-cross-origin"
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>
    </body></html>
""".trimIndent()

@Composable
fun TelaPrincipalApp(activity: MainActivity, isInPip: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var abaPrincipal by remember { mutableStateOf(AbaPrincipal.NAVEGADOR) }
    var canalAtual by remember { mutableStateOf<Canal?>(null) }
    var telaCheia by remember { mutableStateOf(false) }
    var mostrarSiteCompleto by remember { mutableStateOf(false) }
    var modoYoutubeWebDireto by remember { mutableStateOf(false) }
    var mensagemStatus by remember { mutableStateOf<String?>(null) }

    var listaCanais by remember {
        mutableStateOf(carregarCanaisSalvos(context).ifEmpty {
            listOf(
                Canal(nome = "Globoplay (Ao Vivo)", url = "https://globoplay.globo.com/agora-na-tv/", isWebSite = true),
                Canal(nome = "SBT Ao Vivo (Site)", url = "https://www.sbt.com.br/ao-vivo", isWebSite = true),
                Canal(nome = "Band Ao Vivo (Site)", url = "https://www.band.uol.com.br/ao-vivo", isWebSite = true)
            )
        })
    }
    var pesquisaTv by remember { mutableStateOf("") }
    var linkDigitadoTv by remember { mutableStateOf("") }
    var carregandoTv by remember { mutableStateOf(false) }
    var canalParaMover by remember { mutableStateOf<Canal?>(null) }

    var linkEpg by remember { mutableStateOf("https://epg.lat/files/br.xml.gz") }
    var epgDados by remember { mutableStateOf(EpgDados()) }
    var pesquisaEpg by remember { mutableStateOf("") }
    var carregandoEpg by remember { mutableStateOf(false) }

    var canaisYouTube by remember { mutableStateOf(carregarYouTubeSalvos(context)) }
    var modoFeedYt by remember { mutableStateOf(true) }
    var pastaYtAtual by remember { mutableStateOf("Todos") }
    var pesquisaYt by remember { mutableStateOf("") }
    var feedYtVideos by remember { mutableStateOf<List<VideoYouTubeFeed>>(emptyList()) }
    var carregandoYt by remember { mutableStateOf(false) }
    var canalYtParaMover by remember { mutableStateOf<CanalYouTube?>(null) }

    var seekeeQuery by remember { mutableStateOf("") }
    var seekeeEmBusca by remember { mutableStateOf(false) }
    var seekeeResultadoBusca by remember { mutableStateOf(BuscaSeekeeResultado()) }
    var seekeeModoAba by remember { mutableStateOf("Populares") }
    var seekeeTipoPopular by remember { mutableStateOf("Pra você") }
    var seekeeCatalogo by remember { mutableStateOf<List<MidiaItem>>(emptyList()) }
    var carregandoSeekee by remember { mutableStateOf(false) }
    var catOrd by remember { mutableStateOf("Mais buscados") }
    var catTipo by remember { mutableStateOf("Tudo") }
    var catGen by remember { mutableStateOf("Tudo") }
    var catPais by remember { mutableStateOf("Tudo") }
    var catAno by remember { mutableStateOf("Tudo") }
    var midiaAberta by remember { mutableStateOf<MidiaItem?>(null) }
    var episodiosMidia by remember { mutableStateOf<List<EpisodioMidia>>(emptyList()) }
    var tempSel by remember { mutableStateOf(1) }
    var epSel by remember { mutableStateOf(1) }

    fun atualizarCanais(nova: List<Canal>) { listaCanais = nova; salvarCanais(context, nova) }
    fun atualizarCanaisYt(nova: List<CanalYouTube>) { canaisYouTube = nova; salvarYouTube(context, nova) }

    fun carregarCatalogo(tipo: String) {
        carregandoSeekee = true
        scope.launch { seekeeCatalogo = buscarCatalogoPopularSeekee(tipo); carregandoSeekee = false }
    }

    fun pesquisarSeekee(qRaw: String) {
        val q = qRaw.trim()
        if (q.isBlank()) { seekeeEmBusca = false; return }
        if (q.startsWith("http://", true) || q.startsWith("https://", true)) {
            canalAtual = Canal(nome = q.take(36), url = q, isWebSite = !q.contains(".m3u8", true)); return
        }
        seekeeEmBusca = true; midiaAberta = null; carregandoSeekee = true
        scope.launch { seekeeResultadoBusca = buscarSeekeeCompleto(q); carregandoSeekee = false }
    }

    fun abrirDetalhe(item: MidiaItem) {
        midiaAberta = item; tempSel = 1; epSel = 1; episodiosMidia = emptyList()
        if (!item.trailerUrl.isNullOrBlank()) {
            canalAtual = Canal(id = item.id, nome = item.titulo, url = item.trailerUrl, logoUrl = item.posterUrl,
                isWebSite = item.trailerUrl.contains("youtube", true), categoria = CategoriaCanal.FILMES)
        }
        if (item.tvmazeId != null) scope.launch { episodiosMidia = buscarEpisodiosTvMaze(item.tvmazeId) }
    }

    fun carregarFeedYt(pasta: String = pastaYtAtual) {
        val list = when (pasta) { "Todos" -> canaisYouTube; "Favoritos" -> canaisYouTube.filter { it.favorito }; else -> canaisYouTube.filter { it.pasta == pasta } }
        if (list.isEmpty()) { feedYtVideos = emptyList(); return }
        carregandoYt = true; mensagemStatus = "Atualizando feed de inscrições..."
        scope.launch { feedYtVideos = buscarFeedInscricoesYouTube(list); mensagemStatus = null; carregandoYt = false }
    }

    LaunchedEffect(Unit) { carregarCatalogo("Pra você") }
    LaunchedEffect(abaPrincipal, pastaYtAtual, modoFeedYt, canaisYouTube.size) {
        if (abaPrincipal == AbaPrincipal.YOUTUBE && modoFeedYt && canaisYouTube.isNotEmpty()) carregarFeedYt(pastaYtAtual)
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            carregandoYt = true; mensagemStatus = "Importando inscrições..."
            scope.launch {
                val imp = importarCsvYouTube(context, uri)
                if (imp.isNotEmpty()) {
                    val ids = canaisYouTube.map { it.channelId }.toSet()
                    atualizarCanaisYt(imp.filterNot { it.channelId in ids } + canaisYouTube)
                    mensagemStatus = "${imp.size} canais importados!"
                } else mensagemStatus = "Nenhum canal encontrado no CSV."
                carregandoYt = false
            }
        }
    }

    BackHandler(enabled = telaCheia || midiaAberta != null || seekeeEmBusca) {
        when {
            telaCheia -> { telaCheia = false; activity.alterarTelaCheia(false) }
            midiaAberta != null -> midiaAberta = null
            seekeeEmBusca -> seekeeEmBusca = false
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val a = canalAtual
                    if (a != null && a.webOriginalUrl != null) canalAtual = a.copy(url = a.webOriginalUrl, isWebSite = true, webOriginalUrl = null)
                    else mensagemStatus = "Stream indisponível."
                }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    LaunchedEffect(canalAtual) {
        modoYoutubeWebDireto = false
        val c = canalAtual
        if (c != null && !c.isWebSite) { exoPlayer.setMediaItem(MediaItem.fromUri(c.url)); exoPlayer.prepare(); exoPlayer.playWhenReady = true }
        else exoPlayer.pause()
    }

    val playerExpandido = isInPip || telaCheia
    val catalogoFiltrado = remember(seekeeCatalogo, seekeeModoAba, catOrd, catTipo, catGen, catPais, catAno) {
        if (seekeeModoAba == "Populares") seekeeCatalogo
        else {
            val f = seekeeCatalogo.filter {
                (catTipo == "Tudo" || it.tipo.equals(catTipo, true)) && (catGen == "Tudo" || it.generos.any { g -> g.contains(catGen, true) }) &&
                        (catPais == "Tudo" || it.pais.equals(catPais, true)) && (catAno == "Tudo" || it.ano.toString() == catAno)
            }
            when (catOrd) { "Recentes" -> f.sortedByDescending { it.ano }; "Alta Nota" -> f.sortedByDescending { it.nota.toDoubleOrNull() ?: 0.0 }; else -> f.sortedByDescending { it.popularidade } }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E12))
            .then(if (playerExpandido) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
    ) {
        if (canalAtual != null || abaPrincipal != AbaPrincipal.NAVEGADOR || playerExpandido) {
            Box(
                modifier = if (playerExpandido) Modifier.fillMaxSize().background(Color.Black)
                else Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val atual = canalAtual
                if (atual == null) {
                    Text("TV & Seekee Player", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                } else if (atual.isWebSite) {
                    var capturou by remember(atual.url) { mutableStateOf(false) }
                    val chaveCarga = "${atual.url}_$modoYoutubeWebDireto"
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                                settings.useWideViewPort = true; settings.loadWithOverviewMode = true; settings.mediaPlaybackRequiresUserGesture = false
                                webChromeClient = object : WebChromeClient() {
                                    override fun onPermissionRequest(r: PermissionRequest?) { r?.grant(r.resources) }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                                        if (atual.youtubeVideoId != null) return super.shouldInterceptRequest(v, r)
                                        val u = r?.url?.toString() ?: return null
                                        val ehMidia = u.contains(".m3u8", true) || u.contains(".mpd", true) || u.contains(".mp4", true)
                                        val ehAnuncio = u.contains("doubleclick", true) || u.contains("googlesyndication", true) || u.contains("ads", true)
                                        if (ehMidia && !ehAnuncio && !capturou && !mostrarSiteCompleto) {
                                            capturou = true; v?.post { canalAtual = atual.copy(url = u, isWebSite = false, webOriginalUrl = atual.url) }
                                        }
                                        return super.shouldInterceptRequest(v, r)
                                    }
                                    override fun onPageFinished(v: WebView?, url: String?) {
                                        if (!mostrarSiteCompleto && (atual.youtubeVideoId == null || modoYoutubeWebDireto)) {
                                            v?.evaluateJavascript("(function(){function f(){var x=document.querySelector('video');if(x){x.style.position='fixed';x.style.top='0';x.style.left='0';x.style.width='100vw';x.style.height='100vh';x.style.zIndex='2147483647';x.style.background='#000';x.style.objectFit='contain';x.controls=true;}}f();setInterval(f,1500);})();", null)
                                        }
                                    }
                                }
                            }
                        },
                        update = { wv ->
                            if (wv.tag != chaveCarga) {
                                wv.tag = chaveCarga
                                val ytId = atual.youtubeVideoId
                                if (ytId != null && !modoYoutubeWebDireto) {
                                    wv.loadDataWithBaseURL("https://com.tvcanais.app", montarHtmlIframeYoutube(ytId), "text/html", "utf-8", null)
                                } else if (ytId != null) {
                                    wv.loadUrl("https://m.youtube.com/watch?v=$ytId")
                                } else {
                                    wv.loadUrl(atual.url)
                                }
                            }
                        }
                    )
                } else {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } })
                }
                if (telaCheia && !isInPip) {
                    Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) { BotaoAcao("✕ Sair") { telaCheia = false; activity.alterarTelaCheia(false) } }
                }
            }
            if (!playerExpandido && canalAtual != null) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF16161E)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(canalAtual!!.nome, color = Color(0xFF66BB6A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    if (canalAtual!!.youtubeVideoId != null) {
                        BotaoAcao(if (modoYoutubeWebDireto) "Player 1" else "Player 2") { modoYoutubeWebDireto = !modoYoutubeWebDireto }
                        Spacer(Modifier.width(6.dp))
                        BotaoAcao("YT App") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${canalAtual!!.youtubeVideoId}"))) }
                        Spacer(Modifier.width(6.dp))
                    } else if (canalAtual!!.isWebSite) {
                        BotaoAcao(if (mostrarSiteCompleto) "Vídeo" else "Site") { mostrarSiteCompleto = !mostrarSiteCompleto; canalAtual = canalAtual!!.copy() }
                        Spacer(Modifier.width(6.dp))
                    }
                    BotaoAcao("PiP") { activity.entrarModoPip() }
                    Spacer(Modifier.width(6.dp))
                    BotaoAcao("Tela Cheia") { telaCheia = true; activity.alterarTelaCheia(true) }
                    Spacer(Modifier.width(6.dp))
                    BotaoAcao("✕") { canalAtual = null }
                }
            }
        }

        if (!playerExpandido) {
            LazyRow(Modifier.fillMaxWidth().background(Color(0xFF121218)).padding(vertical = 7.dp), contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AbaPrincipal.entries) { aba ->
                    val sel = aba == abaPrincipal
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Color(0xFF2962FF) else Color(0xFF22222C))
                        .clickable { abaPrincipal = aba }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("${aba.icone} ${aba.titulo}", color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (mensagemStatus != null) Text(mensagemStatus!!, color = Color(0xFFFFB74D), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))

            when (abaPrincipal) {
                AbaPrincipal.NAVEGADOR -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (midiaAberta != null || seekeeEmBusca) {
                            BotaoAcao("←") { if (midiaAberta != null) midiaAberta = null else seekeeEmBusca = false }
                            Spacer(Modifier.width(6.dp))
                        }
                        CampoTextoCompacto(seekeeQuery, { seekeeQuery = it }, "🔍 Buscar filme, série, anime, novela ou doc...", Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Button(enabled = !carregandoSeekee && seekeeQuery.isNotBlank(), modifier = Modifier.height(40.dp), contentPadding = PaddingValues(14.dp, 0.dp), onClick = { pesquisarSeekee(seekeeQuery) }) {
                            Text(if (carregandoSeekee) "..." else "Ir", fontSize = 12.sp)
                        }
                    }
                    when {
                        midiaAberta != null -> TelaDetalheSeekee(
                            midiaAberta!!, episodiosMidia, tempSel, epSel, seekeeCatalogo.filter { it.id != midiaAberta!!.id }.take(10),
                            { tempSel = it }, { epSel = it },
                            { it, t, e ->
                                scope.launch {
                                    mensagemStatus = "Localizando fonte de vídeo..."
                                    mostrarSiteCompleto = false
                                    canalAtual = buscarVideoParaEpisodioSeekee(it, t, e, listaCanais)
                                    mensagemStatus = null
                                }
                            },
                            { it ->
                                val fav = Canal("fav_${it.id}", it.titulo, it.trailerUrl ?: it.webUrl ?: "https://duckduckgo.com/?q=${URLEncoder.encode(it.titulo, "UTF-8")}",
                                    it.posterUrl, isWebSite = it.trailerUrl == null, favorito = true)
                                if (listaCanais.none { c -> c.id == fav.id }) atualizarCanais(listOf(fav) + listaCanais)
                                mensagemStatus = "Salvo nos Favoritos!"
                            },
                            { it -> it.webUrl?.let { u -> mostrarSiteCompleto = true; canalAtual = Canal("w_${it.id}", it.titulo, u, it.posterUrl, isWebSite = true) } },
                            { abrirDetalhe(it) }, Modifier.fillMaxWidth().weight(1f)
                        )
                        seekeeEmBusca -> TelaBuscaSeekee(
                            carregandoSeekee, seekeeResultadoBusca, { abrirDetalhe(it) },
                            { w -> mostrarSiteCompleto = false; canalAtual = Canal(nome = w.titulo, url = w.url, isWebSite = true) },
                            { val e = URLEncoder.encode(seekeeQuery.trim(), "UTF-8"); mostrarSiteCompleto = true; canalAtual = Canal(nome = "Busca: $seekeeQuery", url = "https://duckduckgo.com/?q=$e", isWebSite = true) },
                            Modifier.fillMaxWidth().weight(1f)
                        )
                        else -> TelaCatalogoSeekee(
                            seekeeModoAba, seekeeTipoPopular, carregandoSeekee, catalogoFiltrado, catOrd, catTipo, catGen, catPais, catAno,
                            { seekeeModoAba = it; if (it == "Categorias" && seekeeCatalogo.size < 20) carregarCatalogo("Pra você") },
                            { seekeeTipoPopular = it; carregarCatalogo(it) },
                            { catOrd = it }, { catTipo = it }, { catGen = it }, { catPais = it }, { catAno = it },
                            { abrirDetalhe(it) }, Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }
                AbaPrincipal.TV -> TelaTvAoVivo(
                    listaCanais, canalAtual, epgDados, pesquisaTv, linkDigitadoTv, carregandoTv,
                    { pesquisaTv = it }, { linkDigitadoTv = it },
                    {
                        carregandoTv = true; mensagemStatus = "Baixando canais do Brasil..."
                        scope.launch {
                            val br = processarLink("https://iptv-org.github.io/iptv/countries/br.m3u")
                            if (br.size > 1) { atualizarCanais(br + listaCanais); canalAtual = br.first(); pesquisaTv = ""; mensagemStatus = "${br.size} canais carregados!" }
                            else mensagemStatus = "Erro ao baixar lista."
                            carregandoTv = false
                        }
                    },
                    { atualizarCanais(emptyList()); canalAtual = null },
                    { raw ->
                        carregandoTv = true
                        scope.launch {
                            val novos = processarLink(raw.trim()); atualizarCanais(novos + listaCanais)
                            if (novos.isNotEmpty()) canalAtual = novos.first(); linkDigitadoTv = ""; pesquisaTv = ""; carregandoTv = false
                        }
                    },
                    { canalAtual = it },
                    { c -> atualizarCanais(listaCanais.map { if (it.id == c.id) it.copy(favorito = !it.favorito) else it }) },
                    { canalParaMover = it },
                    { c -> atualizarCanais(listaCanais.filterNot { it.id == c.id }) },
                    Modifier.fillMaxWidth().weight(1f)
                )
                AbaPrincipal.GRADE -> TelaGradeTvMap(
                    epgDados, listaCanais, pesquisaEpg, linkEpg, carregandoEpg,
                    { pesquisaEpg = it }, { linkEpg = it },
                    {
                        carregandoEpg = true; mensagemStatus = "Baixando Grade TV Map..."
                        scope.launch {
                            var d = baixarGradeEpg(linkEpg.trim())
                            if (d.canais.isEmpty()) d = baixarGradeEpg("https://epg.pw/xmltv/epg_BR.xml.gz")
                            epgDados = d; mensagemStatus = if (d.canais.isNotEmpty()) "Grade: ${d.canais.size} canais!" else "Erro ao carregar grade."
                            carregandoEpg = false
                        }
                    },
                    { c, nome -> if (c != null) canalAtual = c else mensagemStatus = "'$nome' não está na aba TV Ao Vivo." },
                    Modifier.fillMaxWidth().weight(1f)
                )
                AbaPrincipal.YOUTUBE -> TelaYouTubeFeed(
                    canaisYouTube, feedYtVideos, canalAtual, modoFeedYt, pastaYtAtual, pesquisaYt, carregandoYt,
                    { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*")) },
                    { modoFeedYt = it }, { pastaYtAtual = it }, { pesquisaYt = it }, { carregarFeedYt(pastaYtAtual) },
                    { v -> canalAtual = Canal("yt_${v.videoId}", "${v.canalNome} · ${v.titulo}", "https://www.youtube.com/embed/${v.videoId}?autoplay=1&playsinline=1&rel=0", v.thumbUrl, isWebSite = true, youtubeVideoId = v.videoId) },
                    { yt ->
                        carregandoYt = true
                        scope.launch {
                            val vids = buscarVideosCanalYouTube(yt, 15)
                            if (vids.isNotEmpty()) {
                                feedYtVideos = vids; modoFeedYt = true; val v = vids.first()
                                canalAtual = Canal("yt_${v.videoId}", "${yt.nome} · ${v.titulo}", "https://www.youtube.com/embed/${v.videoId}?autoplay=1&playsinline=1&rel=0", v.thumbUrl, isWebSite = true, youtubeVideoId = v.videoId)
                            }
                            carregandoYt = false
                        }
                    },
                    { yt -> atualizarCanaisYt(canaisYouTube.map { if (it.channelId == yt.channelId) it.copy(favorito = !it.favorito) else it }) },
                    { canalYtParaMover = it },
                    Modifier.fillMaxWidth().weight(1f)
                )
                AbaPrincipal.FAVORITOS -> TelaFavoritos(
                    listaCanais, canalAtual, epgDados, { canalAtual = it },
                    { c -> atualizarCanais(listaCanais.map { if (it.id == c.id) it.copy(favorito = !it.favorito) else it }) },
                    { canalParaMover = it }, { c -> atualizarCanais(listaCanais.filterNot { it.id == c.id }) },
                    Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }

    canalParaMover?.let { mov ->
        AlertDialog(
            onDismissRequest = { canalParaMover = null },
            title = { Text("Mover para categoria:") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoriaCanal.entries.forEach { dest ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (mov.categoria == dest) Color(0xFF2E7D32) else Color(0xFF262632))
                            .clickable { atualizarCanais(listaCanais.map { if (it.id == mov.id) it.copy(categoria = dest) else it }); canalParaMover = null }.padding(12.dp)) {
                            Text("${dest.icone}  ${dest.titulo}", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { canalParaMover = null }) { Text("Fechar") } }
        )
    }
    canalYtParaMover?.let { movYt ->
        AlertDialog(
            onDismissRequest = { canalYtParaMover = null },
            title = { Text("Mover para pasta:") },
            text = {
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pastasPocketTube.filter { it != "Todos" && it != "Favoritos" }) { pasta ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (movYt.pasta == pasta) Color(0xFF2E7D32) else Color(0xFF262632))
                            .clickable { atualizarCanaisYt(canaisYouTube.map { if (it.channelId == movYt.channelId) it.copy(pasta = pasta) else it }); canalYtParaMover = null }.padding(12.dp)) {
                            Text(pasta, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { canalYtParaMover = null }) { Text("Fechar") } }
        )
    }
}
