package com.tvcanais.app

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

fun normalizarUrlJellyfin(raw: String): String {
    var u = raw.trim().trimEnd('/')
    if (u.isBlank()) return ""
    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
    val semProto = u.substringAfter("://")
    if (u.startsWith("http://", true) && !semProto.contains(":") && !semProto.contains("/")) u = "$u:8096"
    return u
}

fun headerAuthJellyfin(cfg: JellyfinConfig): String =
    """MediaBrowser Client="TvCanaisApp", Device="Android", DeviceId="${cfg.deviceId}", Version="1.0"""" +
            if (cfg.accessToken.isNotBlank()) """, Token="${cfg.accessToken}"""" else ""

suspend fun fazerLoginJellyfin(serverRaw: String, usuario: String, senha: String, deviceId: String): Pair<JellyfinConfig?, String?> = withContext(Dispatchers.IO) {
    try {
        val base = normalizarUrlJellyfin(serverRaw)
        if (base.isBlank()) return@withContext null to "Informe o endereço do servidor."
        val con = (URL("$base/Users/AuthenticateByName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", """MediaBrowser Client="TvCanaisApp", Device="Android", DeviceId="$deviceId", Version="1.0"""")
        }
        val body = JSONObject().put("Username", usuario.trim()).put("Pw", senha).toString()
        con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (con.responseCode !in 200..299) return@withContext null to "Falha no login (código ${con.responseCode}). Confira usuário/senha."
        val resp = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        val token = resp.optTexto("AccessToken")
        val uid = resp.optJSONObject("User")?.optTexto("Id") ?: ""
        if (token.isBlank() || uid.isBlank()) return@withContext null to "Resposta inválida do servidor."
        JellyfinConfig(base, usuario.trim(), uid, token, deviceId) to null
    } catch (e: Exception) {
        null to "Não foi possível conectar. Verifique IP, porta (8096) e Wi-Fi."
    }
}

suspend fun buscarViewsJellyfin(cfg: JellyfinConfig): List<JellyfinItem> = withContext(Dispatchers.IO) {
    try {
        val json = lerJsonJellyfin("${cfg.serverUrl}/Users/${cfg.userId}/Views", cfg) ?: return@withContext emptyList()
        val arr = json.optJSONArray("Items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i); val id = o.optTexto("Id").ifBlank { return@mapNotNull null }
            val nome = o.optTexto("Name").ifBlank { "Biblioteca" }
            val hasImg = o.optJSONObject("ImageTags")?.has("Primary") == true
            val poster = if (hasImg) "${cfg.serverUrl}/Items/$id/Images/Primary?maxWidth=400&ApiKey=${cfg.accessToken}" else null
            JellyfinItem(id, nome, "Folder", "Biblioteca · ${o.optTexto("CollectionType").ifBlank { "Mídia" }}", poster, true)
        }
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarItensJellyfin(cfg: JellyfinConfig, parentId: String? = null, ehSerie: Boolean = false, busca: String? = null): List<JellyfinItem> = withContext(Dispatchers.IO) {
    try {
        val url = when {
            !busca.isNullOrBlank() -> "${cfg.serverUrl}/Users/${cfg.userId}/Items?SearchTerm=${URLEncoder.encode(busca.trim(), "UTF-8")}&Recursive=true&IncludeItemTypes=Movie,Series,Episode,Video,TvChannel&Limit=50"
            ehSerie && parentId != null -> "${cfg.serverUrl}/Users/${cfg.userId}/Items?ParentId=$parentId&IncludeItemTypes=Episode&Recursive=true&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending"
            parentId != null -> "${cfg.serverUrl}/Users/${cfg.userId}/Items?ParentId=$parentId&SortBy=SortName&SortOrder=Ascending"
            else -> return@withContext emptyList()
        }
        val json = lerJsonJellyfin(url, cfg) ?: return@withContext emptyList()
        val arr = json.optJSONArray("Items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i); val id = o.optTexto("Id").ifBlank { return@mapNotNull null }
            val nome = o.optTexto("Name").ifBlank { "Item" }; val tipo = o.optTexto("Type")
            val ehPasta = tipo in listOf("Series", "Season", "Folder", "CollectionFolder", "BoxSet")
            val hasImg = o.optJSONObject("ImageTags")?.has("Primary") == true
            val poster = if (hasImg) "${cfg.serverUrl}/Items/$id/Images/Primary?maxWidth=400&ApiKey=${cfg.accessToken}" else null
            val sub = when (tipo) {
                "Episode" -> "T${o.optInt("ParentIndexNumber", 1)} · E${o.optInt("IndexNumber", i + 1)}"
                "Series" -> "Série · ${o.optInt("ProductionYear", 0).let { if (it > 0) "$it" else "Jellyfin" }}"
                "Movie" -> "Filme · ${o.optInt("ProductionYear", 0).let { if (it > 0) "$it" else "Jellyfin" }}"
                else -> tipo.ifBlank { "Mídia" }
            }
            val tituloFinal = if (tipo == "Episode") "$sub · $nome" else nome
            JellyfinItem(id, tituloFinal, tipo, sub, poster, ehPasta)
        }
    } catch (e: Exception) { emptyList() }
}

private fun lerJsonJellyfin(url: String, cfg: JellyfinConfig): JSONObject? {
    val con = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("Authorization", headerAuthJellyfin(cfg))
    }
    if (con.responseCode !in 200..299) return null
    return JSONObject(con.inputStream.bufferedReader().use { it.readText() })
}

fun criarCanalPlaybackJellyfin(cfg: JellyfinConfig, item: JellyfinItem): Canal {
    val direct = if (item.tipo == "TvChannel") "${cfg.serverUrl}/Videos/${item.id}/live.m3u8?ApiKey=${cfg.accessToken}"
    else "${cfg.serverUrl}/Videos/${item.id}/stream?Static=true&ApiKey=${cfg.accessToken}"
    val hls = "${cfg.serverUrl}/Videos/${item.id}/master.m3u8?MediaSourceId=${item.id}&ApiKey=${cfg.accessToken}&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=8000000"
    val cat = if (item.tipo == "Episode") CategoriaCanal.SERIES else if (item.tipo == "TvChannel") CategoriaCanal.TV else CategoriaCanal.FILMES
    return Canal("jf_${item.id}", item.nome, direct, item.posterUrl, isWebSite = false, fallbackStreamUrl = hls, categoria = cat)
}

@Composable
fun TelaJellyfin(
    cfg: JellyfinConfig,
    onSalvarCfg: (JellyfinConfig) -> Unit,
    onTocarCanal: (Canal) -> Unit,
    onAbrirWebUi: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var serverInput by remember(cfg.serverUrl) { mutableStateOf(cfg.serverUrl.ifBlank { "http://192.168.1." }) }
    var userInput by remember(cfg.username) { mutableStateOf(cfg.username) }
    var passInput by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }
    var erroMsg by remember { mutableStateOf<String?>(null) }

    var buscaInput by remember { mutableStateOf("") }
    var itens by remember { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var pilhaPastas by remember { mutableStateOf<List<JellyfinItem>>(emptyList()) }

    fun carregarRaiz(c: JellyfinConfig) {
        if (c.accessToken.isBlank()) return
        carregando = true; erroMsg = null
        scope.launch {
            val views = buscarViewsJellyfin(c)
            itens = views; if (views.isEmpty()) erroMsg = "Sem resposta do servidor (${c.serverUrl})."
            carregando = false
        }
    }

    LaunchedEffect(cfg.accessToken) {
        if (cfg.accessToken.isNotBlank() && itens.isEmpty()) carregarRaiz(cfg)
    }

    if (cfg.accessToken.isBlank()) {
        Column(modifier = modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Conectar ao seu Jellyfin", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("PC e celular precisam estar no mesmo Wi-Fi (ou Tailscale/domínio).", color = Color.Gray, fontSize = 12.sp)
            CampoTextoCompacto(serverInput, { serverInput = it }, "Servidor (ex: http://192.168.1.50:8096)")
            CampoTextoCompacto(userInput, { userInput = it }, "Usuário do Jellyfin")
            CampoTextoCompacto(passInput, { passInput = it }, "Senha (deixe vazio se não tiver)")
            if (erroMsg != null) Text(erroMsg!!, color = Color(0xFFFF8A65), fontSize = 12.sp)
            Button(
                enabled = !carregando && serverInput.isNotBlank() && userInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(42.dp),
                onClick = {
                    carregando = true; erroMsg = null
                    scope.launch {
                        val (novoCfg, err) = fazerLoginJellyfin(serverInput, userInput, passInput, cfg.deviceId)
                        if (novoCfg != null) { onSalvarCfg(novoCfg); carregarRaiz(novoCfg) } else erroMsg = err
                        carregando = false
                    }
                }
            ) { Text(if (carregando) "Conectando..." else "Entrar no Jellyfin") }
        }
        return
    }

    Column(modifier = modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pilhaPastas.isNotEmpty() || buscaInput.isNotBlank()) {
                BotaoAcao("←") {
                    if (buscaInput.isNotBlank()) { buscaInput = ""; carregarRaiz(cfg) }
                    else {
                        val novaPilha = pilhaPastas.dropLast(1); pilhaPastas = novaPilha
                        if (novaPilha.isEmpty()) carregarRaiz(cfg)
                        else scope.launch { carregando = true; itens = buscarItensJellyfin(cfg, novaPilha.last().id, novaPilha.last().tipo == "Series"); carregando = false }
                    }
                }
            }
            CampoTextoCompacto(buscaInput, { buscaInput = it }, "🔍 Buscar no seu Jellyfin...", Modifier.weight(1f))
            BotaoAcao("Ir") {
                if (buscaInput.isNotBlank()) {
                    carregando = true; scope.launch { itens = buscarItensJellyfin(cfg, busca = buscaInput); carregando = false }
                }
            }
            BotaoAcao("🌐 Web") { onAbrirWebUi(cfg.serverUrl) }
            BotaoAcao("Sair") { onSalvarCfg(JellyfinConfig(serverUrl = cfg.serverUrl, username = cfg.username)); itens = emptyList(); pilhaPastas = emptyList() }
        }
        if (erroMsg != null) Text(erroMsg!!, color = Color(0xFFFF8A65), fontSize = 12.sp)
        if (carregando) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFAA00FF)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                items(itens, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF191922))
                            .clickable {
                                if (item.ehPastaOuSerie) {
                                    pilhaPastas = pilhaPastas + item; carregando = true
                                    scope.launch { itens = buscarItensJellyfin(cfg, item.id, item.tipo == "Series"); carregando = false }
                                } else onTocarCanal(criarCanalPlaybackJellyfin(cfg, item))
                            }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF282038)), contentAlignment = Alignment.Center) {
                            if (!item.posterUrl.isNullOrBlank()) {
                                AsyncImage(item.posterUrl, item.nome, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else Text(if (item.ehPastaOuSerie) "📁" else "▶", fontSize = 20.sp, color = Color.White)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.nome, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(item.subtitulo, color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(if (item.ehPastaOuSerie) ">" else "▶", color = Color(0xFFCE93D8), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
    }
}
