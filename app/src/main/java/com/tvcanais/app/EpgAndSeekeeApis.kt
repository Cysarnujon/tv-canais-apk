package com.tvcanais.app

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

suspend fun baixarGradeEpg(url: String): EpgDados = withContext(Dispatchers.IO) {
    try {
        val con = URL(url).openConnection().apply {
            connectTimeout = 12000; readTimeout = 15000; setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val pb = PushbackInputStream(con.getInputStream(), 2)
        val sig = ByteArray(2); val len = pb.read(sig)
        if (len > 0) pb.unread(sig, 0, len)
        val stream: InputStream = if (len == 2 && sig[0] == 0x1f.toByte() && sig[1] == 0x8b.toByte()) GZIPInputStream(pb) else pb

        val parser = Xml.newPullParser(); parser.setInput(stream, "UTF-8")
        val agora = System.currentTimeMillis()
        val jMin = agora - 30 * 60_000L; val jMax = agora + 12 * 3600_000L
        val canaisMap = linkedMapOf<String, CanalEpgInfo>()
        val programasMap = mutableMapOf<String, MutableList<ProgramaEpg>>()
        var chId = ""; var chNome = ""; var chLogo: String? = null
        var pCh = ""; var pIni = 0L; var pFim = 0L; var pTit = ""
        var inCh = false; var inPr = false

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> { inCh = true; chId = parser.getAttributeValue(null, "id") ?: ""; chNome = chId; chLogo = null }
                    "display-name" -> if (inCh) { parser.nextText().trim().let { if (it.isNotBlank()) chNome = it } }
                    "icon" -> if (inCh) { chLogo = parser.getAttributeValue(null, "src") }
                    "programme" -> {
                        inPr = true; pCh = parser.getAttributeValue(null, "channel") ?: ""
                        pIni = parsearDataXmltv(parser.getAttributeValue(null, "start"))
                        pFim = parsearDataXmltv(parser.getAttributeValue(null, "stop")); pTit = ""
                    }
                    "title" -> if (inPr) { pTit = parser.nextText().trim() }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (parser.name == "channel" && inCh) {
                    if (chId.isNotBlank()) canaisMap[chId] = CanalEpgInfo(chId, chNome, chLogo)
                    inCh = false
                } else if (parser.name == "programme" && inPr) {
                    if (pCh.isNotBlank() && pTit.isNotBlank() && pFim > jMin && pIni < jMax) {
                        programasMap.getOrPut(pCh) { mutableListOf() }.add(ProgramaEpg(pCh, pTit, pIni, pFim))
                    }
                    inPr = false
                }
            }
            ev = parser.next()
        }
        EpgDados(canaisMap.values.toList(), programasMap.mapValues { (_, v) -> v.sortedBy { it.inicioMs } })
    } catch (e: Exception) { EpgDados() }
}

fun parsearDataXmltv(valor: String?): Long {
    if (valor.isNullOrBlank()) return 0L
    return try {
        val l = valor.trim()
        val fmt = if (l.contains("+") || l.contains("-")) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss"
        SimpleDateFormat(fmt, Locale.US).parse(if (fmt.endsWith("Z")) l else l.take(14))?.time ?: 0L
    } catch (e: Exception) { 0L }
}

fun formatarHora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun normalizarNome(texto: String): String {
    val sem = texto.replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\[[^]]*\\]"), "")
    return Normalizer.normalize(sem, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")
}

fun encontrarCanalNaLista(lista: List<Canal>, epgId: String, epgNome: String): Canal? {
    val idNorm = normalizarNome(epgId.substringBefore("."))
    val nomeNorm = normalizarNome(epgNome)
    return lista.firstOrNull { c ->
        val cTvg = c.tvgId?.let { normalizarNome(it.substringBefore(".")) }
        val cNome = normalizarNome(c.nome)
        (cTvg != null && (cTvg == normalizarNome(epgId) || cTvg == idNorm)) ||
                cNome == nomeNorm || (nomeNorm.length >= 3 && cNome.contains(nomeNorm)) || (cNome.length >= 3 && nomeNorm.contains(cNome))
    }
}

fun buscarProgramaAtual(epg: EpgDados, canal: Canal, agoraMs: Long): ProgramaEpg? {
    if (epg.canais.isEmpty()) return null
    canal.tvgId?.takeIf { it.isNotBlank() }?.let { tvg ->
        epg.programasPorCanal[tvg]?.firstOrNull { agoraMs in it.inicioMs..it.fimMs }?.let { return it }
    }
    val cNomeNorm = normalizarNome(canal.nome)
    val epgCanal = epg.canais.firstOrNull {
        val eNorm = normalizarNome(it.nome)
        eNorm == cNomeNorm || (eNorm.length >= 3 && cNomeNorm.contains(eNorm))
    } ?: return null
    return epg.programasPorCanal[epgCanal.id]?.firstOrNull { agoraMs in it.inicioMs..it.fimMs }
}

suspend fun buscarVideoParaEpisodioSeekee(item: MidiaItem, temp: Int, ep: Int, listaCanais: List<Canal>): Canal = withContext(Dispatchers.IO) {
    val tituloNorm = normalizarNome(item.titulo)
    val naLista = listaCanais.firstOrNull { c ->
        val cNorm = normalizarNome(c.nome)
        !c.isWebSite && (cNorm.contains(tituloNorm) || tituloNorm.contains(cNorm))
    }
    if (naLista != null) return@withContext naLista

    if (!item.trailerUrl.isNullOrBlank() && item.trailerUrl.contains(".mp4", true)) {
        return@withContext Canal("${item.id}_mp4", item.titulo, item.trailerUrl, item.posterUrl, isWebSite = false)
    }

    val mp4Archive = buscarMp4ArchiveOrg(item.titulo)
    if (mp4Archive != null) {
        return@withContext Canal("${item.id}_arch", "${item.titulo} (Grátis HD)", mp4Archive, item.posterUrl, isWebSite = false)
    }

    val queryWeb = if (item.tipo == "Filmes") "${item.titulo} assistir dublado portugues" else "${item.titulo} temporada $temp episodio $ep assistir dublado"
    val links = buscarWebDuckDuckGo(queryWeb)
    val primeiroSite = links.firstOrNull()
    val label = if (item.tipo == "Filmes") item.titulo else "${item.titulo} · T$temp E$ep"

    if (primeiroSite != null) {
        return@withContext Canal("${item.id}_${temp}_$ep", "$label (${primeiroSite.dominio})", primeiroSite.url, item.posterUrl, isWebSite = true)
    }

    val enc = URLEncoder.encode(queryWeb, "UTF-8")
    Canal("${item.id}_${temp}_$ep", label, "https://duckduckgo.com/?q=$enc", item.posterUrl, isWebSite = true)
}

suspend fun buscarMp4ArchiveOrg(titulo: String): String? = withContext(Dispatchers.IO) {
    try {
        val q = URLEncoder.encode("title:(\"$titulo\") AND mediatype:(movies)", "UTF-8")
        val docs = JSONObject(lerTextoUrl("https://archive.org/advancedsearch.php?q=$q&fl[]=identifier&rows=3&output=json"))
            .optJSONObject("response")?.optJSONArray("docs") ?: return@withContext null
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).optTexto("identifier").ifBlank { continue }
            val files = JSONObject(lerTextoUrl("https://archive.org/metadata/$id")).optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val name = files.getJSONObject(j).optTexto("name")
                if (name.endsWith(".mp4", true) && !name.contains("sample", true)) {
                    return@withContext "https://archive.org/download/$id/${Uri.encode(name, "/")}"
                }
            }
        }
        null
    } catch (e: Exception) { null }
}

suspend fun buscarCatalogoPopularSeekee(tipoFiltro: String): List<MidiaItem> = coroutineScope {
    when (tipoFiltro) {
        "Filmes" -> buscarFilmesItunes("filme acao aventura 2025 2026")
        "Séries" -> buscarSeriesTvMaze("Séries")
        "Anime" -> buscarTopAnimesJikan()
        "Novelas" -> buscarNovelasTvMaze()
        "Documentários" -> buscarDocumentarios()
        else -> {
            val f = async { buscarFilmesItunes("cinema 2025").take(5) }
            val s = async { buscarSeriesTvMaze("Séries").take(6) }
            val a = async { buscarTopAnimesJikan().take(5) }
            val n = async { buscarNovelasTvMaze().take(4) }
            val d = async { buscarDocumentarios().take(4) }
            (a.await() + s.await() + f.await() + n.await() + d.await()).sortedByDescending { it.popularidade }
        }
    }
}

suspend fun buscarSeekeeCompleto(query: String): BuscaSeekeeResultado = coroutineScope {
    val qEnc = URLEncoder.encode(query.trim(), "UTF-8")
    val tvTask = async(Dispatchers.IO) { buscarSeriesTvMazePorNome(qEnc) }
    val animeTask = async(Dispatchers.IO) { buscarAnimesJikanPorNome(qEnc) }
    val filmesTask = async(Dispatchers.IO) { buscarFilmesItunes(query.trim()).take(6) }
    val webTask = async(Dispatchers.IO) { buscarWebDuckDuckGo("$query assistir dublado portugues") }
    BuscaSeekeeResultado((tvTask.await() + animeTask.await() + filmesTask.await()).distinctBy { it.titulo.lowercase() }, webTask.await())
}

suspend fun buscarSeriesTvMaze(tipoAlvo: String): List<MidiaItem> = withContext(Dispatchers.IO) {
    try {
        val arr = JSONArray(lerTextoUrl("https://api.tvmaze.com/shows?page=0"))
        val lista = mutableListOf<MidiaItem>()
        for (i in 0 until arr.length()) parsearShowTvMaze(arr.getJSONObject(i), tipoAlvo)?.let { lista.add(it) }
        lista.sortedByDescending { it.popularidade }.take(35)
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarSeriesTvMazePorNome(qEnc: String): List<MidiaItem> = withContext(Dispatchers.IO) {
    try {
        val arr = JSONArray(lerTextoUrl("https://api.tvmaze.com/search/shows?q=$qEnc"))
        val lista = mutableListOf<MidiaItem>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).optJSONObject("show")?.let { parsearShowTvMaze(it, "") }?.let { lista.add(it) }
        lista
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarNovelasTvMaze(): List<MidiaItem> = coroutineScope {
    val q1 = async(Dispatchers.IO) { buscarSeriesTvMazePorNome("telenovela") }
    val q2 = async(Dispatchers.IO) { buscarSeriesTvMazePorNome("novela") }
    val q3 = async(Dispatchers.IO) { buscarSeriesTvMazePorNome("amor") }
    (q1.await() + q2.await() + q3.await()).distinctBy { it.id }.map { it.copy(tipo = "Novelas") }.take(25)
}

suspend fun buscarDocumentarios(): List<MidiaItem> = coroutineScope {
    val q1 = async(Dispatchers.IO) { buscarSeriesTvMazePorNome("documentary") }
    val q2 = async(Dispatchers.IO) { buscarFilmesItunes("documentario") }
    (q1.await() + q2.await()).map { it.copy(tipo = "Documentários") }.distinctBy { it.titulo }.take(25)
}

fun parsearShowTvMaze(obj: JSONObject, tipoForcado: String): MidiaItem? {
    val id = obj.optInt("id", 0); val nome = obj.optTexto("name")
    if (nome.isBlank()) return null
    val generos = mutableListOf<String>()
    obj.optJSONArray("genres")?.let { g -> for (j in 0 until g.length()) generos.add(traduzirGenero(g.optString(j))) }
    val typeRaw = obj.optTexto("type")
    val tipo = if (tipoForcado.isNotBlank()) tipoForcado else when {
        generos.any { it.equals("Anime", true) } -> "Anime"
        typeRaw.equals("Documentary", true) -> "Documentários"
        else -> "Séries"
    }
    val ano = obj.optTexto("premiered").take(4).toIntOrNull() ?: 0
    val paisCode = obj.optJSONObject("network")?.optJSONObject("country")?.optTexto("code")?.ifBlank { null }
        ?: obj.optJSONObject("webChannel")?.optJSONObject("country")?.optTexto("code")?.ifBlank { null } ?: "US"
    val rawScore = obj.optJSONObject("rating")?.optDouble("average")
    val nota = if (rawScore != null && !rawScore.isNaN() && rawScore > 0) String.format(Locale.US, "%.1f", rawScore) else "7.8"
    val poster = obj.optJSONObject("image")?.optTexto("medium")?.ifBlank { null }
    val pop = obj.optInt("weight", 50) * 850 + (nota.toDoubleOrNull()?.times(1000)?.toInt() ?: 0)
    return MidiaItem("tvmaze_$id", nome, tipo, ano, traduzirPais(paisCode), generos.ifEmpty { listOf("Drama") },
        limparHtml(obj.optTexto("summary")), poster, nota, pop, id, webUrl = obj.optTexto("url").ifBlank { null })
}

suspend fun buscarEpisodiosTvMaze(tvmazeId: Int): List<EpisodioMidia> = withContext(Dispatchers.IO) {
    try {
        val arr = JSONArray(lerTextoUrl("https://api.tvmaze.com/shows/$tvmazeId/episodes"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val num = o.optInt("number", i + 1)
            EpisodioMidia(o.optInt("season", 1), num, o.optTexto("name").ifBlank { "Episódio $num" })
        }
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarTopAnimesJikan(): List<MidiaItem> = withContext(Dispatchers.IO) {
    try {
        val js = try { lerTextoUrl("https://api.jikan.moe/v4/top/anime?limit=25") } catch (e: Exception) { lerTextoUrl("https://jikan.lucashdo.com/v1/top/anime?limit=25") }
        parsearListaJikan(js)
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarAnimesJikanPorNome(qEnc: String): List<MidiaItem> = withContext(Dispatchers.IO) {
    try {
        val js = try { lerTextoUrl("https://api.jikan.moe/v4/anime?q=$qEnc&limit=10") } catch (e: Exception) { lerTextoUrl("https://jikan.lucashdo.com/v1/anime?q=$qEnc&limit=10") }
        parsearListaJikan(js)
    } catch (e: Exception) { emptyList() }
}

fun parsearListaJikan(jsonStr: String): List<MidiaItem> {
    val data = JSONObject(jsonStr).optJSONArray("data") ?: return emptyList()
    val lista = mutableListOf<MidiaItem>()
    for (i in 0 until data.length()) {
        val o = data.getJSONObject(i); val titulo = o.optTexto("title").ifBlank { continue }
        val rawScore = o.optDouble("score")
        val nota = if (!rawScore.isNaN() && rawScore > 0) String.format(Locale.US, "%.1f", rawScore) else "8.2"
        val ano = o.optInt("year", 0).let { if (it > 0) it else o.optJSONObject("aired")?.optTexto("from")?.take(4)?.toIntOrNull() ?: 0 }
        val poster = o.optJSONObject("images")?.optJSONObject("jpg")?.optTexto("image_url")?.ifBlank { null }
        val generos = mutableListOf("Anime")
        o.optJSONArray("genres")?.let { g -> for (k in 0 until g.length()) generos.add(traduzirGenero(g.getJSONObject(k).optTexto("name"))) }
        lista.add(MidiaItem("jikan_${o.optInt("mal_id", i)}", titulo, "Anime", ano, "Japão", generos.distinct(),
            o.optTexto("synopsis"), poster, nota, (o.optInt("members", 45000) / 10).coerceAtLeast(10000),
            trailerUrl = null, webUrl = o.optTexto("url").ifBlank { null }))
    }
    return lista
}

suspend fun buscarFilmesItunes(termo: String): List<MidiaItem> = withContext(Dispatchers.IO) {
    try {
        val enc = URLEncoder.encode(termo, "UTF-8")
        val res = JSONObject(lerTextoUrl("https://itunes.apple.com/search?term=$enc&media=movie&country=br&lang=pt_br&limit=25")).optJSONArray("results") ?: return@withContext emptyList()
        val lista = mutableListOf<MidiaItem>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i); val nome = o.optTexto("trackName").ifBlank { continue }
            lista.add(MidiaItem("itunes_${o.optLong("trackId", i.toLong())}", nome, "Filmes", o.optTexto("releaseDate").take(4).toIntOrNull() ?: 2024, "EUA",
                listOf(o.optTexto("primaryGenreName").ifBlank { "Filme" }), o.optTexto("longDescription").ifBlank { o.optTexto("shortDescription") },
                o.optTexto("artworkUrl100").replace("100x100bb", "400x600bb").ifBlank { null }, "8.1", 88000 - i * 1800,
                trailerUrl = o.optTexto("previewUrl").ifBlank { null }, webUrl = o.optTexto("trackViewUrl").ifBlank { null }))
        }
        lista
    } catch (e: Exception) { emptyList() }
}

suspend fun buscarWebDuckDuckGo(query: String): List<ResultadoWeb> = withContext(Dispatchers.IO) {
    try {
        val html = lerTextoUrl("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}")
        val links = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val res = mutableListOf<ResultadoWeb>()
        for (i in links.indices) {
            if (res.size >= 10) break
            val urlLimpa = extrairUrlDuckDuckGo(links[i].groupValues[1])
            if (!urlLimpa.startsWith("http")) continue
            if (urlLimpa.contains("youtube.com", true) || urlLimpa.contains("youtu.be", true)) continue
            res.add(ResultadoWeb(limparHtml(links[i].groupValues[2]), urlLimpa, Uri.parse(urlLimpa).host?.removePrefix("www.") ?: urlLimpa,
                if (i < snippets.size) limparHtml(snippets[i].groupValues[1]) else ""))
        }
        res
    } catch (e: Exception) { emptyList() }
}

fun extrairUrlDuckDuckGo(rawHref: String): String = try {
    val full = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
    Uri.parse(full).getQueryParameter("uddg") ?: full
} catch (e: Exception) { rawHref }

fun lerTextoUrl(url: String): String = URL(url).openConnection().apply {
    connectTimeout = 9000; readTimeout = 9000; setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
}.getInputStream().bufferedReader().use { it.readText() }

fun traduzirGenero(g: String): String = when (g.lowercase()) {
    "action" -> "Ação"; "comedy" -> "Comédia"; "horror" -> "Terror"; "sci-fi", "science-fiction" -> "Ficção"
    "mystery" -> "Mistério"; "thriller" -> "Suspense"; "adventure" -> "Aventura"; "fantasy" -> "Fantasia"
    "animation" -> "Animação"; "family" -> "Familiar"; else -> g
}

fun traduzirPais(code: String): String = when (code.uppercase()) {
    "BR" -> "Brasil"; "US" -> "EUA"; "KR" -> "Coreia"; "JP" -> "Japão"; "MX" -> "México"; "GB" -> "Reino Unido"; else -> code
}
