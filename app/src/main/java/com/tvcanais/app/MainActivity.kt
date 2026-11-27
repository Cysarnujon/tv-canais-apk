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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

enum class AbaCategoria(val titulo: String, val icone: String) {
    INICIO("Início", "🔍"),
    FAVORITOS("Favoritos", "⭐"),
    TV("Canais", "📺"),
    FILMES("Filmes", "🎬"),
    SERIES("Séries", "🎞️"),
    ANIMES("Animes", "⛩️")
}

data class Canal(
    val id: String = UUID.randomUUID().toString(),
    val nome: String,
    val url: String,
    val isWebSite: Boolean = false,
    val webOriginalUrl: String? = null,
    val categoria: AbaCategoria = AbaCategoria.TV,
    val favorito: Boolean = false
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

    var abrirDialogAdicionar by remember { mutableStateOf(false) }
    var linkDigitado by remember { mutableStateOf("") }
    var categoriaNovoLink by remember { mutableStateOf(AbaCategoria.TV) }

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

    // Filtra a lista de acordo com a aba selecionada e o texto de pesquisa
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

    val playerExpandido = isInPip || telaCheia

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12))
            // Respeita a barra de relógio no topo e os 3 botões do Android embaixo!
            .then(
                if (playerExpandido) Modifier
                else Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
            )
    ) {
        // 1. BLOCO FIXO DO PLAYER NO TOPO
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
                        text = "Pesquise, adicione um link ou toque em Canais Brasil",
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

            // Botão discreto de sair apenas quando está em Tela Cheia
            if (telaCheia && !isInPip) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    BotaoAcaoPlayer(texto = "✕ Sair da Tela Cheia") {
                        telaCheia = false
                        activity.alterarTelaCheia(false)
                    }
                }
            }
        }

        if (!playerExpandido) {
            // 2. BARRA DEDICADA DO PLAYER (PiP, Tela Cheia e Canal Atual - nunca tampada pela barra de status!)
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
                    if (canalAtual!!.isWebSite) {
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

            // 3. ABAS DE CATEGORIAS (Início, Favoritos, Canais, Filmes, Séries, Animes)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121218))
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AbaCategoria.entries) { aba ->
                    val selecionada = aba == abaAtual
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selecionada) Color(0xFF2E7D32) else Color(0xFF22222C))
                            .clickable { abaAtual = aba }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
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

            // 4. BARRA DE PESQUISA + BOTÃO ADICIONAR LINK
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textoPesquisa,
                    onValueChange = { textoPesquisa = it },
                    placeholder = {
                        Text(
                            text = "Pesquisar em ${abaAtual.titulo}...",
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        categoriaNovoLink = if (abaAtual == AbaCategoria.INICIO || abaAtual == AbaCategoria.FAVORITOS) {
                            AbaCategoria.TV
                        } else {
                            abaAtual
                        }
                        abrirDialogAdicionar = true
                    }
                ) {
                    Text("+ Link")
                }
            }

            // Botões rápidos na página inicial (Início)
            if (abaAtual == AbaCategoria.INICIO) {
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
                            mensagemStatus = "Baixando canais abertos do Brasil..."
                            scope.launch {
                                val canaisBr = processarLink(
                                    url = "https://iptv-org.github.io/iptv/countries/br.m3u",
                                    categoriaPadrao = AbaCategoria.TV,
                                    autoCategorizar = true
                                )
                                if (canaisBr.size > 1) {
                                    atualizarCanais(canaisBr + listaCanais)
                                    canalAtual = canaisBr.first()
                                    mensagemStatus = "${canaisBr.size} canais adicionados!"
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
                        onClick = {
                            atualizarCanais(emptyList())
                            canalAtual = null
                            mensagemStatus = "Lista limpa."
                        }
                    ) {
                        Text("Limpar", fontSize = 12.sp)
                    }
                }
            }

            if (mensagemStatus != null) {
                Text(
                    text = mensagemStatus!!,
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(color = Color(0xFF262630))

            // 5. LISTA ROLÁVEL (LIMITADA ACIMA DOS 3 BOTÕES DO ANDROID)
            if (canaisFiltrados.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (textoPesquisa.isNotBlank()) {
                            "Nenhum resultado para \"$textoPesquisa\""
                        } else {
                            "Nenhum item em ${abaAtual.titulo}.\nToque em '+ Link' para adicionar."
                        },
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(canaisFiltrados, key = { it.id }) { canal ->
                        val estaTocando = canalAtual?.id == canal.id

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (estaTocando) Color(0xFF1F2A38) else Color(0xFF191920))
                                .clickable { canalAtual = canal }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 62.dp, height = 40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (estaTocando) Color(0xFF2E7D32) else Color(0xFF2A2A35)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (estaTocando) "▶ VIVO" else "▶ PLAY",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
                                Text(
                                    text = "${canal.categoria.icone} ${canal.categoria.titulo}",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }

                            // Botão Favorito (Estrela)
                            Text(
                                text = if (canal.favorito) "⭐" else "☆",
                                color = if (canal.favorito) Color(0xFFFFD54F) else Color.Gray,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable {
                                        val nova = listaCanais.map {
                                            if (it.id == canal.id) it.copy(favorito = !it.favorito) else it
                                        }
                                        atualizarCanais(nova)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )

                            // Botão Mover para outra aba
                            Text(
                                text = "⇄",
                                color = Color(0xFF64B5F6),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { canalParaMover = canal }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )

                            // Botão Remover
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

    // POPUP: ADICIONAR NOVO LINK E ESCOLHER ABA
    if (abrirDialogAdicionar) {
        AlertDialog(
            onDismissRequest = { abrirDialogAdicionar = false },
            title = { Text("Adicionar Link ou Lista") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkDigitado,
                        onValueChange = { linkDigitado = it },
                        placeholder = { Text("Cole link .m3u, .m3u8 ou site") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Salvar em qual aba?", fontSize = 13.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    val destinos = listOf(AbaCategoria.TV, AbaCategoria.FILMES, AbaCategoria.SERIES, AbaCategoria.ANIMES)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(destinos) { dest ->
                            val selecionado = dest == categoriaNovoLink
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (selecionado) Color(0xFF2E7D32) else Color(0xFF2C2C38))
                                    .clickable { categoriaNovoLink = dest }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${dest.icone} ${dest.titulo}",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !carregando && linkDigitado.isNotBlank(),
                    onClick = {
                        val urlLimpa = linkDigitado.trim()
                        val destino = categoriaNovoLink
                        abrirDialogAdicionar = false
                        carregando = true
                        scope.launch {
                            val novos = processarLink(
                                url = urlLimpa,
                                categoriaPadrao = destino,
                                autoCategorizar = false
                            )
                            atualizarCanais(novos + listaCanais)
                            if (novos.isNotEmpty()) {
                                canalAtual = novos.first()
                            }
                            linkDigitado = ""
                            carregando = false
                        }
                    }
                ) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { abrirDialogAdicionar = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // POPUP: MOVER CANAL PARA OUTRA ABA
    val movendo = canalParaMover
    if (movendo != null) {
        val destinos = listOf(AbaCategoria.TV, AbaCategoria.FILMES, AbaCategoria.SERIES, AbaCategoria.ANIMES)
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
                                    val novaLista = listaCanais.map {
                                        if (it.id == movendo.id) it.copy(categoria = destino) else it
                                    }
                                    atualizarCanais(novaLista)
                                    canalParaMover = null
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${destino.icone}  ${destino.titulo}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { canalParaMover = null }) {
                    Text("Fechar")
                }
            }
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

fun salvarCanais(context: Context, canais: List<Canal>) {
    val arr = JSONArray()
    canais.forEach { c ->
        val obj = JSONObject()
        obj.put("id", c.id)
        obj.put("nome", c.nome)
        obj.put("url", c.url)
        obj.put("isWebSite", c.isWebSite)
        obj.put("categoria", c.categoria.name)
        obj.put("favorito", c.favorito)
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
            val catNome = obj.optString("categoria", AbaCategoria.TV.name)
            val cat = AbaCategoria.entries.find { it.name == catNome } ?: AbaCategoria.TV
            list.add(
                Canal(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    nome = obj.optString("nome", "Canal"),
                    url = obj.optString("url", ""),
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

suspend fun processarLink(
    url: String,
    categoriaPadrao: AbaCategoria,
    autoCategorizar: Boolean
): List<Canal> = withContext(Dispatchers.IO) {
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
            var catTemporaria = categoriaPadrao

            for (linha in conteudo.lines()) {
                val l = linha.trim()
                if (l.startsWith("#EXTINF")) {
                    nomeTemporario = l.substringAfterLast(",", "Canal").trim()
                    if (autoCategorizar) {
                        val lower = l.lowercase()
                        catTemporaria = when {
                            lower.contains("anime") -> AbaCategoria.ANIMES
                            lower.contains("serie") || lower.contains("série") -> AbaCategoria.SERIES
                            lower.contains("movie") || lower.contains("filme") || lower.contains("cine") -> AbaCategoria.FILMES
                            else -> categoriaPadrao
                        }
                    } else {
                        catTemporaria = categoriaPadrao
                    }
                } else if (l.startsWith("http")) {
                    canaisEncontrados.add(
                        Canal(
                            nome = nomeTemporario,
                            url = l,
                            isWebSite = false,
                            categoria = catTemporaria
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
                categoria = categoriaPadrao
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
                categoria = categoriaPadrao
            )
        )
    }
}
