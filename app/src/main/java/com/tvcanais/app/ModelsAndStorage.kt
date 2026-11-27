package com.tvcanais.app

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class AbaPrincipal(val titulo: String, val icone: String) {
    NAVEGADOR("Início", "🏠"),
    JELLYFIN("Jellyfin", "🟣"),
    TV("TV Ao Vivo", "📺"),
    YOUTUBE("YouTube", "▶️"),
    FAVORITOS("Favoritos", "⭐")
}

enum class CategoriaCanal(val titulo: String, val icone: String) {
    TV("Canais", "📺"),
    FILMES("Filmes", "🎬"),
    SERIES("Séries", "🎞️"),
    ANIMES("Animes", "⛩️"),
    NOVELAS("Novelas", "🌹"),
    DOCS("Docs", "🌍")
}

data class JellyfinConfig(
    val serverUrl: String = "", val username: String = "", val userId: String = "",
    val accessToken: String = "", val deviceId: String = UUID.randomUUID().toString()
)

data class JellyfinItem(
    val id: String, val nome: String, val tipo: String, val subtitulo: String = "",
    val posterUrl: String? = null, val ehPastaOuSerie: Boolean = false
)

data class Canal(
    val id: String = UUID.randomUUID().toString(),
    val nome: String, val url: String, val logoUrl: String? = null, val tvgId: String? = null,
    val isWebSite: Boolean = false, val webOriginalUrl: String? = null, val fallbackStreamUrl: String? = null,
    val youtubeVideoId: String? = null, val categoria: CategoriaCanal = CategoriaCanal.TV, val favorito: Boolean = false
)

data class CanalYouTube(
    val channelId: String, val nome: String, val url: String,
    val pasta: String = "Geral", val favorito: Boolean = false
)

data class VideoYouTubeFeed(
    val videoId: String, val titulo: String, val canalId: String, val canalNome: String,
    val pasta: String, val publicadoIso: String, val dataFormatada: String, val thumbUrl: String
)

data class ProgramaEpg(val channelId: String, val titulo: String, val inicioMs: Long, val fimMs: Long)
data class CanalEpgInfo(val id: String, val nome: String, val logoUrl: String? = null)
data class EpgDados(val canais: List<CanalEpgInfo> = emptyList(), val programasPorCanal: Map<String, List<ProgramaEpg>> = emptyMap())
data class ResultadoWeb(val titulo: String, val url: String, val dominio: String, val resumo: String)

val pastasPocketTube = listOf(
    "Todos", "Favoritos", "Geral", "Entretenimento",
    "Tecnologia", "Filmes & Séries", "Animes", "Games", "Notícias", "Música"
)

fun JSONObject.optTexto(chave: String): String {
    if (isNull(chave)) return ""
    val v = optString(chave, "")
    return if (v == "null") "" else v
}

fun limparHtml(texto: String): String =
    if (texto.isBlank()) "" else Html.fromHtml(texto, Html.FROM_HTML_MODE_COMPACT).toString().trim()

fun salvarPastasYt(context: Context, pastas: List<String>) {
    val arr = JSONArray(); pastas.forEach { arr.put(it) }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).edit().putString("yt_pastas", arr.toString()).apply()
}

fun carregarPastasYt(context: Context): List<String> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).getString("yt_pastas", null) ?: return pastasPocketTube
    return try {
        val arr = JSONArray(json)
        val list = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        if (list.isEmpty()) pastasPocketTube else list
    } catch (e: Exception) { pastasPocketTube }
}

fun salvarJellyfinConfig(context: Context, cfg: JellyfinConfig) {
    val obj = JSONObject().apply {
        put("serverUrl", cfg.serverUrl); put("username", cfg.username)
        put("userId", cfg.userId); put("accessToken", cfg.accessToken); put("deviceId", cfg.deviceId)
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).edit().putString("jellyfin_cfg", obj.toString()).apply()
}

fun carregarJellyfinConfig(context: Context): JellyfinConfig {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).getString("jellyfin_cfg", null) ?: return JellyfinConfig()
    return try {
        val o = JSONObject(json)
        JellyfinConfig(o.optTexto("serverUrl"), o.optTexto("username"), o.optTexto("userId"),
            o.optTexto("accessToken"), o.optTexto("deviceId").ifBlank { UUID.randomUUID().toString() })
    } catch (e: Exception) { JellyfinConfig() }
}

fun salvarCanais(context: Context, canais: List<Canal>) {
    val arr = JSONArray()
    canais.forEach { c ->
        arr.put(JSONObject().apply {
            put("id", c.id); put("nome", c.nome); put("url", c.url); put("logoUrl", c.logoUrl ?: "")
            put("tvgId", c.tvgId ?: ""); put("isWebSite", c.isWebSite); put("categoria", c.categoria.name); put("favorito", c.favorito)
        })
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).edit().putString("canais", arr.toString()).apply()
}

fun carregarCanaisSalvos(context: Context): List<Canal> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).getString("canais", null) ?: return emptyList()
    return try {
        val list = mutableListOf<Canal>(); val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val cat = CategoriaCanal.entries.find { it.name == obj.optTexto("categoria") } ?: CategoriaCanal.TV
            list.add(Canal(obj.optTexto("id").ifBlank { UUID.randomUUID().toString() }, obj.optTexto("nome").ifBlank { "Canal" },
                obj.optTexto("url"), obj.optTexto("logoUrl").ifBlank { null }, obj.optTexto("tvgId").ifBlank { null },
                obj.optBoolean("isWebSite", false), categoria = cat, favorito = obj.optBoolean("favorito", false)))
        }
        list
    } catch (e: Exception) { emptyList() }
}

fun salvarYouTube(context: Context, canais: List<CanalYouTube>) {
    val arr = JSONArray()
    canais.forEach { c ->
        arr.put(JSONObject().apply { put("channelId", c.channelId); put("nome", c.nome); put("url", c.url); put("pasta", c.pasta); put("favorito", c.favorito) })
    }
    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).edit().putString("yt_canais", arr.toString()).apply()
}

fun carregarYouTubeSalvos(context: Context): List<CanalYouTube> {
    val json = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE).getString("yt_canais", null) ?: return emptyList()
    return try {
        val list = mutableListOf<CanalYouTube>(); val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(CanalYouTube(obj.optTexto("channelId"), obj.optTexto("nome").ifBlank { "Canal YT" },
                obj.optTexto("url"), obj.optTexto("pasta").ifBlank { "Geral" }, obj.optBoolean("favorito", false)))
        }
        list
    } catch (e: Exception) { emptyList() }
}

suspend fun processarLink(url: String): List<Canal> = withContext(Dispatchers.IO) {
    try {
        val con = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "Mozilla/5.0") }
        val conteudo = con.getInputStream().bufferedReader().use { it.readText() }
        val ehLista = conteudo.contains("#EXTM3U") && conteudo.contains("#EXTINF") &&
                !conteudo.contains("#EXT-X-STREAM-INF") && !conteudo.contains("#EXT-X-TARGETDURATION")
        if (ehLista) {
            val canais = mutableListOf<Canal>(); var nomeTemp = "Canal"; var logoTemp: String? = null; var idTemp: String? = null
            val rLogo = Regex("""tvg-logo="([^"]*)""""); val rId = Regex("""tvg-id="([^"]*)"""")
            for (linha in conteudo.lines()) {
                val l = linha.trim()
                if (l.startsWith("#EXTINF")) {
                    nomeTemp = l.substringAfterLast(",", "Canal").trim()
                    logoTemp = rLogo.find(l)?.groupValues?.getOrNull(1)?.ifBlank { null }
                    idTemp = rId.find(l)?.groupValues?.getOrNull(1)?.ifBlank { null }
                } else if (l.startsWith("http")) canais.add(Canal(nome = nomeTemp, url = l, logoUrl = logoTemp, tvgId = idTemp))
            }
            if (canais.isNotEmpty()) return@withContext canais
        }
        val ehStream = conteudo.contains("#EXTM3U") || url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".mpd", true)
        listOf(Canal(nome = url.removePrefix("https://").removePrefix("http://").take(35), url = url, isWebSite = !ehStream))
    } catch (e: Exception) {
        val ehVideo = url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".mpd", true)
        listOf(Canal(nome = url.removePrefix("https://").removePrefix("http://").take(35), url = url, isWebSite = !ehVideo))
    }
}

suspend fun importarCsvYouTube(context: Context, uri: Uri): List<CanalYouTube> = withContext(Dispatchers.IO) {
    try {
        val texto = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext emptyList()
        val resultado = mutableListOf<CanalYouTube>()
        for (linhaRaw in texto.lines()) {
            val linha = linhaRaw.trim().removePrefix("\uFEFF"); if (linha.isBlank()) continue
            val cols = dividirLinhaCsv(linha); if (cols.size < 2) continue
            val c0 = cols[0].trim(); val c1 = cols[1].trim(); val c2 = if (cols.size >= 3) cols[2].trim() else ""
            if (c0.contains("Channel", true) || c0.contains("ID do canal", true)) continue
            val id = when { c0.startsWith("UC") -> c0; c1.contains("/channel/") -> c1.substringAfter("/channel/").substringBefore("/"); else -> c0 }
            val urlCanal = if (c1.startsWith("http")) c1 else "https://www.youtube.com/channel/$id"
            if (id.isNotBlank()) resultado.add(CanalYouTube(id, c2.ifBlank { c1.ifBlank { id } }, urlCanal))
        }
        resultado
    } catch (e: Exception) { emptyList() }
}

fun dividirLinhaCsv(linha: String): List<String> {
    val campos = mutableListOf<String>(); val atual = StringBuilder(); var aspas = false
    for (c in linha) when { c == '"' -> aspas = !aspas; c == ',' && !aspas -> { campos.add(atual.toString()); atual.clear() }; else -> atual.append(c) }
    campos.add(atual.toString()); return campos
}

suspend fun buscarVideosCanalYouTube(yt: CanalYouTube, limite: Int = 15): List<VideoYouTubeFeed> = withContext(Dispatchers.IO) {
    try {
        val con = URL("https://www.youtube.com/feeds/videos.xml?channel_id=${yt.channelId}").openConnection().apply {
            connectTimeout = 6000; readTimeout = 6000; setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val parser = Xml.newPullParser(); parser.setInput(con.getInputStream(), "UTF-8")
        val videos = mutableListOf<VideoYouTubeFeed>(); var dentro = false; var vidId = ""; var titulo = ""; var pub = ""; var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (parser.name == "entry") { dentro = true; vidId = ""; titulo = ""; pub = "" }
                else if (dentro) when (parser.name) { "videoId" -> vidId = parser.nextText().trim(); "title" -> titulo = parser.nextText().trim(); "published" -> pub = parser.nextText().trim() }
            } else if (ev == XmlPullParser.END_TAG && parser.name == "entry" && dentro) {
                if (vidId.isNotBlank()) videos.add(VideoYouTubeFeed(vidId, titulo.ifBlank { "Vídeo" }, yt.channelId, yt.nome, yt.pasta, pub, formatarDataIsoBr(pub), "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"))
                dentro = false; if (videos.size >= limite) break
            }
            ev = parser.next()
        }
        videos
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarFeedInscricoesYouTube(canais: List<CanalYouTube>): List<VideoYouTubeFeed> = coroutineScope {
    val todos = mutableListOf<VideoYouTubeFeed>()
    for (grupo in canais.take(24).chunked(8)) {
        grupo.map { async(Dispatchers.IO) { buscarVideosCanalYouTube(it, 5) } }.awaitAll().forEach { todos.addAll(it) }
    }
    todos.sortedByDescending { it.publicadoIso }
}

fun formatarDataIsoBr(iso: String): String = try {
    val dt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19))
    if (dt != null) SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(dt) else iso.take(10)
} catch (e: Exception) { iso.take(10) }
