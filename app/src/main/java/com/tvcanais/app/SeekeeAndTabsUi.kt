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
fun LinhaFiltroSeekee(opcoes: List<String>, selecionado: String, aoSelecionar: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(opcoes) { op ->
            val ativo = op == selecionado
            Box(
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (ativo) Color(0xFF1F3A68) else Color.Transparent)
                    .clickable { aoSelecionar(op) }.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(op, color = if (ativo) Color(0xFF64B5F6) else Color.LightGray, fontSize = 12.sp, fontWeight = if (ativo) FontWeight.Bold else FontWeight.Normal)
            }
        }
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
fun TelaDetalheSeekee(
    item: MidiaItem, episodios: List<EpisodioMidia>, temporadaSel: Int, episodioSel: Int,
    semelhantes: List<MidiaItem>, onTemporada: (Int) -> Unit, onEpisodio: (Int) -> Unit,
    onAssistirWeb: (MidiaItem, Int, Int) -> Unit, onFavoritar: (MidiaItem) -> Unit,
    onAbrirPaginaInfo: (MidiaItem) -> Unit, onAbrirSemelhante: (MidiaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val temporadas = episodios.map { it.temporada }.distinct().ifEmpty { listOf(1) }
    val epsDaTemp = episodios.filter { it.temporada == temporadaSel }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(item.titulo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${item.tipo} · ${if (item.ano > 0) "${item.ano} · " else ""}${item.generos.joinToString(" · ")}", color = Color.Gray, fontSize = 12.sp)
            if (item.sinopse.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(item.sinopse, color = Color.LightGray, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF191922)).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onAssistirWeb(item, temporadaSel, episodioSel) }) {
                    Text("▶", fontSize = 18.sp, color = Color(0xFF4CAF50))
                    Text("Assistir Web", fontSize = 11.sp, color = Color.LightGray)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onFavoritar(item) }) {
                    Text("⭐", fontSize = 18.sp, color = Color(0xFFFFD54F))
                    Text("Favoritar", fontSize = 11.sp, color = Color.LightGray)
                }
                if (!item.webUrl.isNullOrBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onAbrirPaginaInfo(item) }) {
                        Text("🌐", fontSize = 18.sp)
                        Text("Página Info", fontSize = 11.sp, color = Color.LightGray)
                    }
                }
            }
        }
        if (item.tipo != "Filmes") {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(temporadas) { temp ->
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (temp == temporadaSel) Color(0xFF2962FF) else Color(0xFF22222C))
                                .clickable { onTemporada(temp) }.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Temporada $temp", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val totalCaps = if (epsDaTemp.isNotEmpty()) epsDaTemp.size else 12
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Episódios", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("$totalCaps episódios", color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val numeros = if (epsDaTemp.isNotEmpty()) epsDaTemp.map { it.numero } else (1..12).toList()
                    items(numeros) { num ->
                        Box(
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (num == episodioSel) Color(0xFF424252) else Color(0xFF1F1F28))
                                .clickable { onEpisodio(num); onAssistirWeb(item, temporadaSel, num) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$num", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (semelhantes.isNotEmpty()) {
            item {
                Text("Títulos semelhantes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(semelhantes) { sem ->
                        Column(modifier = Modifier.width(105.dp).clickable { onAbrirSemelhante(sem) }) {
                            Box(modifier = Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF22222C))) {
                                if (!sem.posterUrl.isNullOrBlank()) {
                                    AsyncImage(model = sem.posterUrl, contentDescription = sem.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Box(modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                                    Text("⭐ ${sem.nota}", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(sem.titulo, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelaBuscaSeekee(
    carregando: Boolean, resultado: BuscaSeekeeResultado,
    onAbrirMidia: (MidiaItem) -> Unit, onAbrirWeb: (ResultadoWeb) -> Unit, onBuscaCompletaWeb: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (carregando) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF2962FF)) }
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(resultado.midias) { item ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF171720))
                    .clickable { onAbrirMidia(item) }.padding(12.dp)
            ) {
                Text(item.titulo, color = Color(0xFF64B5F6), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${item.tipo} · ${if (item.ano > 0) "${item.ano} · " else ""}${item.generos.take(3).joinToString(" · ")}", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.sinopse.ifBlank { "Toque para ver episódios, fontes e detalhes." }, color = Color.Gray, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (!item.posterUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(10.dp))
                        AsyncImage(model = item.posterUrl, contentDescription = item.titulo, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 86.dp, height = 120.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252532)),
                    onClick = { onAbrirMidia(item) }
                ) {
                    Text("Assistir / Ver Episódios  >", color = Color.White, fontSize = 13.sp)
                }
            }
        }
        items(resultado.linksWeb) { web ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF15151C))
                    .clickable { onAbrirWeb(web) }.padding(12.dp)
            ) {
                Text(web.titulo, color = Color(0xFF64B5F6), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (web.resumo.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(web.resumo, color = Color.LightGray, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("🌐 ${web.dominio}", color = Color.Gray, fontSize = 11.sp)
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onBuscaCompletaWeb) {
                Text("🌐 Abrir busca completa no Navegador", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TelaCatalogoSeekee(
    modoAba: String, tipoPopular: String, carregando: Boolean, catalogo: List<MidiaItem>,
    catOrd: String, catTipo: String, catGen: String, catPais: String, catAno: String,
    onMudarModoAba: (String) -> Unit, onMudarTipoPopular: (String) -> Unit,
    onCatOrd: (String) -> Unit, onCatTipo: (String) -> Unit, onCatGen: (String) -> Unit,
    onCatPais: (String) -> Unit, onCatAno: (String) -> Unit, onAbrirMidia: (MidiaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("Populares" to "Pesquisas populares", "Categorias" to "Categorias").forEach { (chave, rotulo) ->
                val ativo = modoAba == chave
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onMudarModoAba(chave) }) {
                    Text(rotulo, color = if (ativo) Color.White else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.width(if (ativo) 28.dp else 0.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                }
            }
        }
        if (modoAba == "Populares") {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Pra você", "Filmes", "Séries", "Anime", "Novelas", "Documentários")) { tipo ->
                    val sel = tipo == tipoPopular
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) Color(0xFF2962FF) else Color.Transparent)
                            .clickable { onMudarTipoPopular(tipo) }.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(tipo, color = if (sel) Color.White else Color.LightGray, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LinhaFiltroSeekee(listOf("Mais buscados", "Recentes", "Alta Nota"), catOrd, onCatOrd)
                LinhaFiltroSeekee(listOf("Tudo", "Filmes", "Séries", "Anime", "Novelas", "Documentários"), catTipo, onCatTipo)
                LinhaFiltroSeekee(listOf("Tudo", "Ação", "Drama", "Comédia", "Terror", "Suspense", "Ficção", "Animação"), catGen, onCatGen)
                LinhaFiltroSeekee(listOf("Tudo", "EUA", "Brasil", "Coreia", "Japão", "México"), catPais, onCatPais)
                LinhaFiltroSeekee(listOf("Tudo", "2026", "2025", "2024", "2023", "2022"), catAno, onCatAno)
            }
        }
        if (carregando) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF2962FF)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(catalogo) { index, item ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onAbrirMidia(item) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(width = 68.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF22222C))) {
                            if (!item.posterUrl.isNullOrBlank()) {
                                AsyncImage(model = item.posterUrl, contentDescription = item.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            val cor = when (index) { 0 -> Color(0xFFE53935); 1 -> Color(0xFFFB8C00); 2 -> Color(0xFFFFB300); else -> Color(0xAA333340) }
                            Box(modifier = Modifier.clip(RoundedCornerShape(bottomEnd = 8.dp)).background(cor).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.titulo, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🔥 ${item.popularidade}", color = Color(0xFFFF7043), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${if (item.ano > 0) "${item.ano} · " else ""}${item.pais} · ${item.generos.take(3).joinToString(" · ")}", color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(item.tipo, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
