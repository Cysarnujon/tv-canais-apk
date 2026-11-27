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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
    var linkDigitado by remember { mutableStateOf("") }

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
                mensagemStatus = if (novos.size > 1) "${novos.size} canais adicionados!" else "Canal adicionado!"
            } else {
                mensagemStatus = "Nenhum canal encontrado nesse link."
            }
            linkDigitado = ""
            textoPesquisa = ""
            carregando = false
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
                        text = "Cole um link abaixo ou toque em Canais Brasil",
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
            // 2. BARRA DEDICADA DO PLAYER (PiP e Tela Cheia)
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

            // 3. ABAS DE CATEGORIAS
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
                            .background(if (selecionada) Color(0xFF2E7D32) else Color(0xFF22222C))
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

            // 4. BARRA DE PESQUISA COMPACTA
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
                        dica = "🔍 Pesquisar canal pelo nome...",
                        modifier = Modifier.weight(1f)
                    )
                    if (textoPesquisa.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        BotaoAcaoPlayer(texto = "✕") { textoPesquisa = "" }
                    }
                }

                // Se colou um link por engano na barra de pesquisa, oferece botão direto para adicionar
                if (textoPesquisa.trim().startsWith("http", ignoreCase = true)) {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        onClick = { adicionarLinkDireto(textoPesquisa) }
                    ) {
                        Text("➕ Adicionar este link na lista", fontSize = 12.sp)
                    }
                }

                // Na aba Início: Botões Canais Brasil (Grátis) / Limpar + Campo de colar link LOGO ABAIXO
                if (abaAtual == AbaCategoria.INICIO) {
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

                    // CAMPO PARA COLAR LINK LOGO ABAIXO DE CANAIS BRASIL GRÁTIS (SEM POPUP)
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

            // 5. LISTA ROLÁVEL DOS CANAIS
            if (canaisFiltrados.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (textoPesquisa.isNotBlank()) {
                            "Nenhum canal encontrado para \"$textoPesquisa\""
                        } else {
                            "Nenhum canal em ${abaAtual.titulo}.\nCole um link acima ou toque em Canais Brasil."
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

    // POPUP PARA MOVER CANAL PARA OUTRA ABA (QUANDO TOCAR NO BOTÃO ⇄)
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
                    canaisEncontrados.add(
                        Canal(
                            nome = nomeTemporario,
                            url = l,
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
