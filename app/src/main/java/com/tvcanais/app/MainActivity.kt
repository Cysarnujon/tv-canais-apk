package com.tvcanais.app

import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class Canal(
    val nome: String,
    val url: String,
    val isWebSite: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TelaPrincipalTV()
                }
            }
        }
    }
}

@Composable
fun TelaPrincipalTV() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var linkDigitado by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }

    var listaCanais by remember {
        mutableStateOf(
            listOf(
                Canal("Globoplay (Site Oficial)", "https://globoplay.globo.com/agora-na-tv/", isWebSite = true),
                Canal("SBT Ao Vivo (Site)", "https://www.sbt.com.br/ao-vivo", isWebSite = true),
                Canal("Band Ao Vivo (Site)", "https://www.band.uol.com.br/ao-vivo", isWebSite = true)
            )
        )
    }

    var canalAtual by remember { mutableStateOf<Canal?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(canalAtual) {
        val canal = canalAtual
        if (canal != null && !canal.isWebSite) {
            exoPlayer.setMediaItem(MediaItem.fromUri(canal.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.pause()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // BLOCO FIXO DO PLAYER NO TOPO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val atual = canalAtual
            if (atual == null) {
                Text(
                    text = "Adicione um link ou escolha um canal abaixo",
                    color = Color.LightGray
                )
            } else if (atual.isWebSite) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.grant(request.resources)
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
        }

        // BARRA DE ADICIONAR LINK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = linkDigitado,
                onValueChange = { linkDigitado = it },
                placeholder = { Text("Cole link .m3u, .m3u8 ou site") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !carregando && linkDigitado.isNotBlank(),
                onClick = {
                    val urlLimpa = linkDigitado.trim()
                    carregando = true
                    scope.launch {
                        val novosCanais = processarLink(urlLimpa)
                        listaCanais = novosCanais + listaCanais
                        if (novosCanais.size == 1) {
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

        HorizontalDivider(color = Color.DarkGray)

        // LISTA ROLÁVEL ABAIXO DO PLAYER
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(listaCanais) { canal ->
                val estaTocando = canalAtual == canal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (estaTocando) Color(0xFF2C2C3E) else Color.Transparent)
                        .clickable { canalAtual = canal }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = canal.nome,
                            color = if (estaTocando) Color(0xFF4CAF50) else Color.White,
                            fontWeight = if (estaTocando) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = if (canal.isWebSite) "Modo Navegador (Web)" else "Modo Player Nativo (Stream)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    if (estaTocando) {
                        Text("TOCANDO", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider(color = Color(0xFF222222))
            }
        }
    }
}

suspend fun processarLink(url: String): List<Canal> = withContext(Dispatchers.IO) {
    try {
        val conteudo = URL(url).readText()

        val ehListaDeCanais = conteudo.contains("#EXTM3U") &&
                conteudo.contains("#EXTINF") &&
                !conteudo.contains("#EXT-X-STREAM-INF") &&
                !conteudo.contains("#EXT-X-TARGETDURATION")

        if (ehListaDeCanais) {
            val canaisEncontrados = mutableListOf<Canal>()
            var nomeTemporario = "Canal sem nome"
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
