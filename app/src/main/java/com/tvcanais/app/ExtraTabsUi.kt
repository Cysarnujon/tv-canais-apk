package com.tvcanais.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

@Composable
fun TelaTvAoVivo(
    listaCanais: List<Canal>, canalAtual: Canal?, epgDados: EpgDados,
    pesquisaTv: String, linkDigitadoTv: String, carregandoTv: Boolean,
    onPesquisa: (String) -> Unit, onLinkDigitado: (String) -> Unit,
    onCarregarBrasil: () -> Unit, onLimpar: () -> Unit, onAdicionarLink: (String) -> Unit,
    onTocar: (Canal) -> Unit, onFavoritar: (Canal) -> Unit,
    onMover: (Canal) -> Unit, onRemover: (Canal) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CampoTextoCompacto(pesquisaTv, onPesquisa, "🔍 Pesquisar canal pelo nome...", Modifier.weight(1f))
            if (pesquisaTv.isNotEmpty()) { Spacer(Modifier.width(6.dp)); BotaoAcao("✕") { onPesquisa("") } }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !carregandoTv, modifier = Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(10.dp, 0.dp), onClick = onCarregarBrasil) {
                Text(if (carregandoTv) "Carregando..." else "📺 Canais Brasil (Grátis)", fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.height(38.dp), contentPadding = PaddingValues(14.dp, 0.dp), onClick = onLimpar) {
                Text("Limpar", fontSize = 12.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CampoTextoCompacto(linkDigitadoTv, onLinkDigitado, "🔗 Cole link .m3u, .m3u8 ou site aqui", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = !carregandoTv && linkDigitadoTv.isNotBlank(), modifier = Modifier.height(40.dp), contentPadding = PaddingValues(16.dp, 0.dp), onClick = { onAdicionarLink(linkDigitadoTv) }) {
                Text(if (carregandoTv) "..." else "Add", fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF262630))
        val filtrados = remember(listaCanais, pesquisaTv) {
            val q = pesquisaTv.trim(); if (q.isEmpty()) listaCanais else listaCanais.filter { it.nome.contains(q, true) }
        }
        val agoraMs = System.currentTimeMillis()
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            itemsIndexed(filtrados, key = { _, c -> c.id }) { index, canal ->
                CardCanalLista(index, canal, canalAtual?.id == canal.id, buscarProgramaAtual(epgDados, canal, agoraMs),
                    { onTocar(canal) }, { onFavoritar(canal) }, { onMover(canal) }, { onRemover(canal) })
            }
        }
    }
}

@Composable
fun TelaGradeTvMap(
    epgDados: EpgDados, listaCanais: List<Canal>, pesquisaEpg: String,
    linkEpg: String, carregandoEpg: Boolean, onPesquisa: (String) -> Unit,
    onLinkEpg: (String) -> Unit, onAtualizarGrade: () -> Unit,
    onSelecionarCanal: (Canal?, String) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(4.dp))
        CampoTextoCompacto(pesquisaEpg, onPesquisa, "🔍 Pesquisar canal ou programa na grade...")
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CampoTextoCompacto(linkEpg, onLinkEpg, "Link XMLTV (.xml ou .xml.gz)", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = !carregandoEpg, modifier = Modifier.height(40.dp), onClick = onAtualizarGrade) {
                Text(if (carregandoEpg) "..." else "Atualizar", fontSize = 12.sp)
            }
        }
        val agoraMs = System.currentTimeMillis()
        val filtrada = remember(epgDados, pesquisaEpg) {
            val q = pesquisaEpg.trim()
            if (q.isEmpty()) epgDados.canais
            else epgDados.canais.filter { c -> c.nome.contains(q, true) || epgDados.programasPorCanal[c.id].orEmpty().any { it.titulo.contains(q, true) } }
        }
        if (filtrada.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Toque em 'Atualizar' para carregar a grade TV Map.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                items(filtrada, key = { it.id }) { epgCanal ->
                    val progs = epgDados.programasPorCanal[epgCanal.id].orEmpty()
                    val progAtual = progs.firstOrNull { agoraMs in it.inicioMs..it.fimMs }
                    val proxProg = progs.firstOrNull { it.inicioMs > agoraMs }
                    val canalLista = encontrarCanalNaLista(listaCanais, epgCanal.id, epgCanal.nome)
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF191920))
                            .clickable { onSelecionarCanal(canalLista, epgCanal.nome) }.padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!epgCanal.logoUrl.isNullOrBlank()) {
                                AsyncImage(epgCanal.logoUrl, epgCanal.nome, contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(44.dp, 30.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF252530)))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(epgCanal.nome, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (canalLista != null) {
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E7D32)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    Text("▶ ASSISTIR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (progAtual != null) {
                            val dur = (progAtual.fimMs - progAtual.inicioMs).coerceAtLeast(1L)
                            val pct = ((agoraMs - progAtual.inicioMs).toFloat() / dur).coerceIn(0f, 1f)
                            Text("AGORA (${formatarHora(progAtual.inicioMs)} - ${formatarHora(progAtual.fimMs)}): ${progAtual.titulo}",
                                color = Color(0xFF66BB6A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Color(0xFF66BB6A), trackColor = Color(0xFF2C2C38))
                        } else {
                            Text("Sem programação atual", color = Color.Gray, fontSize = 12.sp)
                        }
                        if (proxProg != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("A SEGUIR (${formatarHora(proxProg.inicioMs)}): ${proxProg.titulo}", color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelaYouTubeFeed(
    canaisYouTube: List<CanalYouTube>, feedVideos: List<VideoYouTubeFeed>,
    canalAtual: Canal?, modoFeed: Boolean, pastaAtual: String,
    pesquisaYt: String, carregandoYt: Boolean,
    onImportarCsv: () -> Unit, onMudarModoFeed: (Boolean) -> Unit,
    onMudarPasta: (String) -> Unit, onPesquisa: (String) -> Unit,
    onAtualizarFeed: () -> Unit, onTocarVideo: (VideoYouTubeFeed) -> Unit,
    onAbrirCanalYt: (CanalYouTube) -> Unit, onFavoritarYt: (CanalYouTube) -> Unit,
    onMoverYt: (CanalYouTube) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !carregandoYt, modifier = Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(10.dp, 0.dp), onClick = onImportarCsv) {
                Text("📂 Importar CSV", fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.height(38.dp), contentPadding = PaddingValues(12.dp, 0.dp), onClick = { onMudarModoFeed(true) }) {
                Text("🔥 Feed", color = if (modoFeed) Color(0xFF66BB6A) else Color.LightGray, fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.height(38.dp), contentPadding = PaddingValues(12.dp, 0.dp), onClick = { onMudarModoFeed(false) }) {
                Text("📋 Canais (${canaisYouTube.size})", color = if (!modoFeed) Color(0xFF66BB6A) else Color.LightGray, fontSize = 12.sp)
            }
        }
        CampoTextoCompacto(pesquisaYt, onPesquisa, "🔍 Pesquisar vídeo ou canal inscrito...")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pastasPocketTube) { pasta ->
                val ativa = pasta == pastaAtual
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (ativa) Color(0xFF2E7D32) else Color(0xFF20202A))
                    .clickable { onMudarPasta(pasta) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(pasta, color = Color.White, fontSize = 11.sp, fontWeight = if (ativa) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (canaisYouTube.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Toque em '📂 Importar CSV' para importar\nsuas inscrições do YouTube e gerar seu Feed!", color = Color.Gray, fontSize = 13.sp)
            }
        } else if (modoFeed) {
            if (carregandoYt && feedVideos.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFE53935)) }
            } else {
                val filtrados = remember(feedVideos, pesquisaYt) {
                    val q = pesquisaYt.trim()
                    if (q.isEmpty()) feedVideos else feedVideos.filter { it.titulo.contains(q, true) || it.canalNome.contains(q, true) }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Feed de Inscrições · $pastaAtual", color = Color.LightGray, fontSize = 12.sp)
                            Text("↻ Atualizar Feed", color = Color(0xFF64B5F6), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onAtualizarFeed() })
                        }
                    }
                    items(filtrados, key = { "${it.canalId}_${it.videoId}" }) { video ->
                        val tocando = canalAtual?.youtubeVideoId == video.videoId
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (tocando) Color(0xFF1F2A38) else Color(0xFF181820)).clickable { onTocarVideo(video) }
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                                AsyncImage(video.thumbUrl, video.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(video.dataFormatada, color = Color.White, fontSize = 10.sp)
                                }
                            }
                            Column(Modifier.padding(10.dp)) {
                                Text(video.titulo, color = if (tocando) Color(0xFF66BB6A) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text("${video.canalNome} · Pasta: ${video.pasta}", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val chFiltrados = remember(canaisYouTube, pastaAtual, pesquisaYt) {
                val p = when (pastaAtual) { "Todos" -> canaisYouTube; "Favoritos" -> canaisYouTube.filter { it.favorito }; else -> canaisYouTube.filter { it.pasta == pastaAtual } }
                val q = pesquisaYt.trim(); if (q.isEmpty()) p else p.filter { it.nome.contains(q, true) }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                itemsIndexed(chFiltrados, key = { _, c -> c.channelId }) { index, yt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF191920))
                            .clickable { onAbrirCanalYt(yt) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFC62828)), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(yt.nome, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Pasta: ${yt.pasta}", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(if (yt.favorito) "⭐" else "☆", fontSize = 18.sp, modifier = Modifier.clickable { onFavoritarYt(yt) }.padding(6.dp))
                        Text("⇄", color = Color(0xFF64B5F6), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onMoverYt(yt) }.padding(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TelaFavoritos(
    listaCanais: List<Canal>, canalAtual: Canal?, epgDados: EpgDados,
    onTocar: (Canal) -> Unit, onFavoritar: (Canal) -> Unit,
    onMover: (Canal) -> Unit, onRemover: (Canal) -> Unit, modifier: Modifier = Modifier
) {
    val favs = remember(listaCanais) { listaCanais.filter { it.favorito } }
    if (favs.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Toque na estrela ⭐ em qualquer canal, filme ou série para salvar aqui.", color = Color.Gray, fontSize = 13.sp)
        }
    } else {
        val agoraMs = System.currentTimeMillis()
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(favs, key = { _, c -> c.id }) { index, canal ->
                CardCanalLista(index, canal, canalAtual?.id == canal.id, buscarProgramaAtual(epgDados, canal, agoraMs),
                    { onTocar(canal) }, { onFavoritar(canal) }, { onMover(canal) }, { onRemover(canal) })
            }
        }
    }
}
