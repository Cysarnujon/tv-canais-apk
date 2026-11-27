package com.tvcanais.app

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun CampoTextoCompacto(valor: String, aoMudar: (String) -> Unit, dica: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C26)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (valor.isEmpty()) Text(dica, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = valor, onValueChange = aoMudar, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFF66BB6A)), modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun BotaoAcao(texto: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B2B38))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(texto, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CardCanalLista(
    index: Int, canal: Canal, estaTocando: Boolean, progAgora: ProgramaEpg?,
    onClick: () -> Unit, onFavoritar: () -> Unit, onMover: () -> Unit, onRemover: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (estaTocando) Color(0xFF1F2A38) else Color(0xFF191920))
            .clickable { onClick() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (estaTocando) Color(0xFF2E7D32) else Color(0xFF252532)),
            contentAlignment = Alignment.Center
        ) {
            if (!canal.logoUrl.isNullOrBlank()) {
                AsyncImage(model = canal.logoUrl, contentDescription = canal.nome, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(if (estaTocando) "▶ VIVO" else "▶ PLAY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(bottomEnd = 6.dp))
                    .background(if (index < 3) Color(0xFFE53935) else Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("${index + 1}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(canal.nome, color = if (estaTocando) Color(0xFF66BB6A) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progAgora != null) {
                Text("Agora (${formatarHora(progAgora.inicioMs)}): ${progAgora.titulo}", color = Color(0xFF81C784), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${canal.categoria.icone} ${canal.categoria.titulo}", color = Color.Gray, fontSize = 11.sp)
        }
        Text(if (canal.favorito) "⭐" else "☆", color = if (canal.favorito) Color(0xFFFFD54F) else Color.Gray, fontSize = 18.sp, modifier = Modifier.clickable { onFavoritar() }.padding(6.dp))
        Text("⇄", color = Color(0xFF64B5F6), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onMover() }.padding(6.dp))
        Text("✕", color = Color.Gray, fontSize = 15.sp, modifier = Modifier.clickable { onRemover() }.padding(6.dp))
    }
}

@Composable
fun TelaInicioCanais(
    listaCanais: List<Canal>, canalAtual: Canal?, epgDados: EpgDados, carregando: Boolean,
    onCarregarLista: (url: String, nome: String) -> Unit, onCarregarTodas: () -> Unit, onLimpar: () -> Unit,
    onTocar: (Canal) -> Unit, onFavoritar: (Canal) -> Unit, onMover: (Canal) -> Unit, onRemover: (Canal) -> Unit,
    modifier: Modifier = Modifier
) {
    val botoesListas = listOf(
        "🇧🇷 Brasil (iptv-com)" to "https://github.com/iptv-com/iptv/raw/refs/heads/main/lists/brazil.m3u",
        "📡 Abertos" to "https://github.com/iptv-com/iptv/raw/main/lists/abertos.m3u",
        "🎬 TV Legal (Helen)" to "https://raw.githubusercontent.com/helenfernanda/gratis/main/iptvlegal.m3u",
        "🌐 MeuTédio" to "https://tv.meuted.io/iptvlegal.m3u",
        "📺 Canais BR" to "https://iptv-org.github.io/iptv/countries/br.m3u",
        "🟣 Pluto TV BR" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/br_pluto.m3u"
    )
    Column(modifier = modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Button(enabled = !carregando, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(12.dp, 0.dp), onClick = onCarregarTodas) {
                    Text(if (carregando) "Carregando..." else "⚡ Todas Listas", fontSize = 12.sp)
                }
            }
            items(botoesListas) { (nome, url) ->
                OutlinedButton(enabled = !carregando, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(10.dp, 0.dp), onClick = { onCarregarLista(url, nome) }) {
                    Text(nome, fontSize = 12.sp)
                }
            }
            item {
                OutlinedButton(modifier = Modifier.height(36.dp), contentPadding = PaddingValues(12.dp, 0.dp), onClick = onLimpar) {
                    Text("🗑️ Limpar", fontSize = 12.sp)
                }
            }
        }
        val agoraMs = System.currentTimeMillis()
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            itemsIndexed(listaCanais, key = { _, c -> c.id }) { index, canal ->
                CardCanalLista(index, canal, canalAtual?.id == canal.id, buscarProgramaAtual(epgDados, canal, agoraMs),
                    { onTocar(canal) }, { onFavoritar(canal) }, { onMover(canal) }, { onRemover(canal) })
            }
        }
    }
}

@Composable
fun TelaFontesWebBusca(
    query: String, carregando: Boolean, canaisLocais: List<Canal>, canalAtual: Canal?, epgDados: EpgDados,
    linksWeb: List<ResultadoWeb>, onTocarCanal: (Canal) -> Unit, onFavoritarCanal: (Canal) -> Unit,
    onMoverCanal: (Canal) -> Unit, onRemoverCanal: (Canal) -> Unit,
    onAbrirFonteWeb: (ResultadoWeb) -> Unit, onBuscaCompletaWeb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val agoraMs = System.currentTimeMillis()
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canaisLocais.isNotEmpty()) {
            item { Text("Canais encontrados na sua lista:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            itemsIndexed(canaisLocais, key = { _, c -> c.id }) { index, canal ->
                CardCanalLista(index, canal, canalAtual?.id == canal.id, buscarProgramaAtual(epgDados, canal, agoraMs),
                    { onTocarCanal(canal) }, { onFavoritarCanal(canal) }, { onMoverCanal(canal) }, { onRemoverCanal(canal) })
            }
        }
        item { Text("Fontes de sites para \"$query\":", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (carregando) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF2962FF)) } }
        } else if (linksWeb.isEmpty()) {
            item { Text("Nenhuma fonte direta encontrada. Tente outro termo ou toque abaixo.", color = Color.Gray, fontSize = 12.sp) }
        } else {
            items(linksWeb) { web ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF171720))
                        .clickable { onAbrirFonteWeb(web) }.padding(12.dp)
                ) {
                    Text(web.titulo, color = Color(0xFF64B5F6), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (web.resumo.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(web.resumo, color = Color.LightGray, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐 ${web.dominio}", color = Color.Gray, fontSize = 11.sp)
                        Text("Abrir fonte >", color = Color(0xFF66BB6A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onBuscaCompletaWeb) {
                Text("🌐 Abrir pesquisa completa no navegador", fontSize = 12.sp)
            }
        }
    }
}
