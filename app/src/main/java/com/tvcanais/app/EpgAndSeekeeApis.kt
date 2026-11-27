package com.tvcanais.app

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
