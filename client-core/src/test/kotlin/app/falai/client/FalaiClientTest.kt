package app.falai.client

import app.falai.server.FalaiServer
import app.falai.server.JdbcDb
import app.falai.server.ServerConfig
import app.falai.server.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Testa o núcleo do app contra o servidor de verdade, simulando aparelhos da família,
 * quedas de conexão e servidor desligado.
 */
class FalaiClientTest {

    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private var port = 0
    private lateinit var adminCode: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableListOf<FalaiClient>()

    private val url get() = "http://127.0.0.1:$port"

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-client").toFile()
        startServer(0)
    }

    private suspend fun startServer(fixedPort: Int) {
        server = FalaiServer(Store(JdbcDb(File(dir, "server.db").path)), ServerConfig(port = fixedPort, mediaDir = File(dir, "server-media")), log = {})
        server.start()?.let { adminCode = it }
        port = server.boundPort()
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.stop() }
        scope.cancel()
        server.stop()
        dir.deleteRecursively()
    }

    private fun newClient(
        name: String,
        delays: List<Long> = listOf(100, 200, 300),
        handshakeTimeoutMs: Long = 20_000,
    ): FalaiClient {
        val c = FalaiClient(
            ClientStore(JdbcDb(File(dir, "$name.db").path)), scope, File(dir, "$name-media"),
            reconnectDelaysMs = delays,
            handshakeTimeoutMs = handshakeTimeoutMs,
        )
        clients += c
        return c
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(8_000) { while (!check()) delay(25) }
        } catch (e: Exception) {
            throw AssertionError("Não aconteceu a tempo: $what")
        }
    }

    private suspend fun online(c: FalaiClient) {
        c.start()
        eventually("ficar online") { c.connection.value == ConnectionState.ONLINE }
    }

    private fun bodies(c: FalaiClient, conv: String) = c.store.messages(conv).map { it.body }

    /** Chefe (admin) e Ana pareados e online, com conversa direta. */
    private suspend fun family(): Triple<FalaiClient, FalaiClient, String> {
        val chefe = newClient("chefe")
        chefe.pair(url, adminCode, "Celular do Chefe", "Chefe")
        online(chefe)
        val code = chefe.createInvite().code
        val ana = newClient("ana")
        ana.pair(url, code, "Celular da Ana", "Ana")
        online(ana)
        val conv = chefe.createConversation("direct", null, listOf(ana.account.value!!.userId))
        eventually("Ana ver a conversa") { ana.store.conversationSummaries(ana.account.value!!.userId).isNotEmpty() }
        return Triple(chefe, ana, conv.id)
    }

    @Test
    fun `pareamento com codigo errado da mensagem clara`() = runBlocking {
        val c = newClient("x")
        val e = assertFailsWith<FalaiClientException> { c.pair(url, "ERRADO1234", "Celular", "Fulano") }
        assertEquals("invalid_code", e.code)
        assertEquals(null, c.store.account())
    }

    @Test
    fun `endereco do servidor errado da erro de rede`() = runBlocking {
        val c = newClient("x")
        val e = assertFailsWith<FalaiClientException> { c.pair("http://127.0.0.1:1", adminCode, "Celular", "Fulano") }
        assertEquals("network", e.code)
    }

    @Test
    fun `conversa em tempo real com nomes e status`() = runBlocking {
        val (chefe, ana, conv) = family()
        val anaId = ana.account.value!!.userId
        val chefeId = chefe.account.value!!.userId

        // Cada lado vê o nome do outro na lista de conversas.
        eventually("nomes") {
            chefe.store.conversationSummaries(chefeId).single().displayName == "Ana" &&
                ana.store.conversationSummaries(anaId).single().displayName == "Chefe"
        }

        chefe.send(conv, "bom dia ☀️")
        eventually("Ana receber") { bodies(ana, conv) == listOf("bom dia ☀️") }
        eventually("Chefe ver ✓") { chefe.store.messages(conv).single().status == MessageStatus.SENT }
        assertEquals(MessageStatus.RECEIVED, ana.store.messages(conv).single().status)

        ana.send(conv, "bom dia, pai!")
        eventually("Chefe receber") { bodies(chefe, conv) == listOf("bom dia ☀️", "bom dia, pai!") }
        assertEquals("bom dia, pai!", chefe.store.conversationSummaries(chefeId).single().lastBody)
    }

    @Test
    fun `mensagens escritas sem conexao saem quando conecta`() = runBlocking {
        val (chefe, ana, conv) = family()
        ana.stop()
        // Ana escreve sem conexão: fica na fila (relógio).
        ana.send(conv, "1")
        ana.send(conv, "2")
        ana.send(conv, "3")
        assertTrue(ana.store.messages(conv).all { it.status == MessageStatus.PENDING })
        delay(300)
        assertEquals(emptyList(), bodies(chefe, conv))

        online(ana)
        eventually("Chefe receber na ordem") { bodies(chefe, conv) == listOf("1", "2", "3") }
        eventually("fila vazia") { ana.store.pending().isEmpty() }
    }

    @Test
    fun `quem estava fora recebe tudo ao voltar`() = runBlocking {
        val (chefe, ana, conv) = family()
        ana.stop()
        repeat(5) { chefe.send(conv, "msg $it") }
        eventually("Chefe enviar tudo") { chefe.store.pending().isEmpty() }

        online(ana)
        eventually("Ana receber pelo sync") { bodies(ana, conv) == (0 until 5).map { "msg $it" } }
    }

    @Test
    fun `servidor cai e volta e o app reconecta sozinho`() = runBlocking {
        val (chefe, ana, conv) = family()
        val samePort = port
        server.stop()
        eventually("perceber a queda") { chefe.connection.value != ConnectionState.ONLINE }

        chefe.send(conv, "escrevi com o servidor fora")
        delay(300)
        assertEquals(MessageStatus.PENDING, chefe.store.messages(conv).single().status)

        startServer(samePort)
        eventually("Chefe voltar") { chefe.connection.value == ConnectionState.ONLINE }
        eventually("Ana voltar") { ana.connection.value == ConnectionState.ONLINE }
        eventually("Ana receber") { bodies(ana, conv) == listOf("escrevi com o servidor fora") }
        eventually("✓ no Chefe") { chefe.store.messages(conv).single().status == MessageStatus.SENT }
    }

    @Test
    fun `aparelho novo da mesma pessoa recebe o historico e as conversas`() = runBlocking {
        val (chefe, ana, conv) = family()
        repeat(3) { ana.send(conv, "a$it") }
        chefe.send(conv, "c0")
        eventually("tudo entregue") { bodies(chefe, conv).size == 4 && ana.store.pending().isEmpty() }

        val code = chefe.createInvite(forUserId = chefe.account.value!!.userId).code
        val tablet = newClient("tablet")
        val acc = tablet.pair(url, code, "Tablet do Chefe", null)
        assertEquals(chefe.account.value!!.userId, acc.userId)
        assertTrue(acc.isAdmin)
        online(tablet)

        eventually("tablet com histórico") { bodies(tablet, conv).toSet() == setOf("a0", "a1", "a2", "c0") }
        // Mensagem do Chefe aparece no tablet como "enviada por mim".
        assertEquals(MessageStatus.SENT, tablet.store.messages(conv).single { it.body == "c0" }.status)
        eventually("tablet com o nome da Ana") {
            tablet.store.conversationSummaries(acc.userId).single().displayName == "Ana"
        }

        // Escrevendo no tablet, aparece no celular do Chefe também.
        tablet.send(conv, "do tablet")
        eventually("celular do Chefe ver") { bodies(chefe, conv).last() == "do tablet" }
        assertEquals(MessageStatus.SENT, chefe.store.messages(conv).last().status)
    }

    @Test
    fun `grupo com tres pessoas`() = runBlocking {
        val (chefe, ana, _) = family()
        val bruno = newClient("bruno")
        bruno.pair(url, chefe.createInvite().code, "Celular do Bruno", "Bruno")
        online(bruno)
        // A tela de "nova conversa" atualiza a lista de pessoas ao abrir.
        chefe.refreshUsers()
        assertTrue(chefe.store.users().any { it.name == "Bruno" })

        val ids = listOf(ana, bruno).map { it.account.value!!.userId }
        val g = chefe.createConversation("group", "Família 💚", ids)
        eventually("Bruno ver o grupo") {
            bruno.store.conversationSummaries(bruno.account.value!!.userId).any { it.displayName == "Família 💚" }
        }
        bruno.send(g.id, "oi gente")
        eventually("Ana e Chefe receberem") {
            bodies(ana, g.id) == listOf("oi gente") && bodies(chefe, g.id) == listOf("oi gente")
        }
        // Ana conhece o Bruno pelo nome (lista de pessoas atualizada).
        eventually("Ana saber o nome do Bruno") { ana.store.userName(bruno.account.value!!.userId) == "Bruno" }
    }

    @Test
    fun `quem nao e admin nao cria convite`() = runBlocking {
        val (_, ana, _) = family()
        val e = assertFailsWith<FalaiClientException> { ana.createInvite() }
        assertEquals("forbidden", e.code)
    }

    @Test
    fun `token invalido para de tentar e avisa`() = runBlocking {
        val c = newClient("hacker")
        c.store.saveAccount(Account(url, "token-falso", "u", "d", false))
        c.start()
        eventually("estado não autorizado") { c.connection.value == ConnectionState.UNAUTHORIZED }
    }

    @Test
    fun `endereco aceita varios formatos`() {
        assertEquals("https://falai.exemplo.com", FalaiClient.normalizeUrl(" falai.exemplo.com/ "))
        assertEquals("http://10.0.2.2:8443", FalaiClient.normalizeUrl("http://10.0.2.2:8443"))
    }

    // ---------- Arquivos (M3) ----------

    private fun randomFile(name: String, size: Int, seed: Int): File =
        File(dir, name).apply { writeBytes(kotlin.random.Random(seed).nextBytes(size)) }

    @Test
    fun `foto vai identica e so baixa quando abre`() = runBlocking {
        val (chefe, ana, conv) = family()
        val foto = randomFile("foto.jpg", 3 * 1024 * 1024 + 7, 1)

        val m = chefe.sendMedia(conv, foto, "image", "image/jpeg", caption = "olha que lindo")
        assertEquals(MessageStatus.PENDING, m.status)
        assertEquals(foto.path, m.localPath)

        eventually("Ana receber a mensagem com anexo") { ana.store.messages(conv).singleOrNull()?.attachment != null }
        val recebida = ana.store.messages(conv).single()
        val att = recebida.attachment!!
        assertEquals("olha que lindo", recebida.body)
        assertEquals("image", att.kind)
        assertEquals(foto.length(), att.size)
        assertEquals(null, recebida.localPath) // ainda não baixou
        assertEquals("📷 Foto · olha que lindo", ana.store.conversationSummaries(ana.account.value!!.userId).single().lastBody)

        val baixado = ana.mediaFile(att)
        kotlin.test.assertContentEquals(foto.readBytes(), baixado.readBytes())
        assertEquals(baixado.path, ana.store.messages(conv).single().localPath)
        eventually("✓ no Chefe") { chefe.store.messages(conv).single().status == MessageStatus.SENT }
        // Baixar de novo usa o que já está no aparelho.
        assertEquals(baixado.path, ana.mediaFile(att).path)
    }

    @Test
    fun `video grande sobe em pedacos mesmo com servidor fora no inicio`() = runBlocking {
        val (chefe, ana, conv) = family()
        val samePort = port
        server.stop()
        eventually("Chefe perceber a queda") { chefe.connection.value != ConnectionState.ONLINE }

        val video = randomFile("video.mp4", 20 * 1024 * 1024 + 99, 2) // 3 pedaços
        chefe.sendMedia(conv, video, "video", "video/mp4")
        delay(500)
        assertEquals(MessageStatus.PENDING, chefe.store.messages(conv).single().status)

        startServer(samePort)
        eventually("Ana receber o vídeo") { ana.store.messages(conv).singleOrNull()?.attachment != null }
        val att = ana.store.messages(conv).single().attachment!!
        assertEquals("🎥 Vídeo", ana.store.conversationSummaries(ana.account.value!!.userId).single().lastBody)
        kotlin.test.assertContentEquals(video.readBytes(), ana.mediaFile(att).readBytes())
    }

    @Test
    fun `audio e texto chegam na ordem em que foram escritos`() = runBlocking {
        val (chefe, ana, conv) = family()
        val audio = randomFile("audio.m4a", 200_000, 3)
        chefe.sendMedia(conv, audio, "audio", "audio/mp4", durationMs = 42_000)
        eventually("áudio chegar") { ana.store.messages(conv).isNotEmpty() }
        chefe.send(conv, "ouviu?")
        eventually("Ana receber os dois") { bodies(ana, conv) == listOf("", "ouviu?") }
        assertEquals("🎤 Áudio (0:42)", ClientStore.preview("", ana.store.messages(conv).first().attachment))
    }

    @Test
    fun `tablet novo ve o anexo antigo e consegue baixar`() = runBlocking {
        val (chefe, ana, conv) = family()
        val doc = randomFile("lista-churrasco.pdf", 150_000, 4)
        ana.sendMedia(conv, doc, "file", "application/pdf")
        eventually("enviado") { ana.store.messages(conv).single().status == MessageStatus.SENT }

        val tablet = newClient("tablet")
        tablet.pair(url, chefe.createInvite(chefe.account.value!!.userId).code, "Tablet", null)
        online(tablet)
        eventually("tablet ver o anexo") { tablet.store.messages(conv).singleOrNull()?.attachment != null }
        val att = tablet.store.messages(conv).single().attachment!!
        assertEquals("lista-churrasco.pdf", att.name)
        kotlin.test.assertContentEquals(doc.readBytes(), tablet.mediaFile(att).readBytes())
        assertEquals("📄 lista-churrasco.pdf", ClientStore.preview("", att))
    }

    @Test
    fun `quem nao esta na conversa nao consegue baixar`() = runBlocking {
        val (chefe, _, conv) = family()
        val foto = randomFile("f.jpg", 5000, 5)
        chefe.sendMedia(conv, foto, "image", "image/jpeg")
        eventually("enviado") { chefe.store.messages(conv).single().status == MessageStatus.SENT }
        val att = chefe.store.messages(conv).single().attachment!!

        val bruno = newClient("bruno")
        bruno.pair(url, chefe.createInvite().code, "Celular", "Bruno")
        val e = assertFailsWith<FalaiClientException> { bruno.mediaFile(att) }
        assertEquals("http_404", e.code)
    }

    // ---------- Reconexão (problemas vistos no teste no celular) ----------

    @Test
    fun `servidor que aceita a conexao mas nao responde nao prende o app`() = runBlocking {
        // Imita a "ponte" do cabo meio caída: aceita a conexão e fica mudo.
        val mudo = java.net.ServerSocket(0)
        val aceitas = mutableListOf<java.net.Socket>()
        val t = Thread { try { while (true) aceitas += mudo.accept() } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        try {
            val c = newClient("x", delays = listOf(100), handshakeTimeoutMs = 500)
            c.store.saveAccount(Account("http://127.0.0.1:${mudo.localPort}", "t", "u", "d", false))
            c.start()
            eventually("conectando") { c.connection.value == ConnectionState.CONNECTING }
            eventually("desistir e avisar") { c.lastError.value?.contains("não respondeu") == true }
            eventually("tentar de novo") { aceitas.size >= 2 }
        } finally {
            mudo.close()
            aceitas.forEach { it.close() }
            t.interrupt()
        }
    }

    @Test
    fun `reconectar agora nao espera os 30 segundos`() = runBlocking {
        val (chefe, ana, conv) = family()
        ana.stop()
        val samePort = port
        server.stop()

        // Ana volta a tentar com espera longa (como depois de muitas falhas no celular).
        val ana2 = FalaiClient(ana.store, scope, File(dir, "ana-media"), reconnectDelaysMs = listOf(60_000))
        clients += ana2
        ana2.send(conv, "Ola")
        ana2.start()
        eventually("Ana desistir da primeira tentativa") { ana2.connection.value == ConnectionState.OFFLINE }
        assertTrue(ana2.lastError.value != null)

        startServer(samePort)
        delay(300)
        assertEquals(ConnectionState.OFFLINE, ana2.connection.value) // ainda esperando os 60s
        ana2.reconnectNow() // app aberto / internet voltou / botão "Reconectar"
        eventually("Ana online na hora") { ana2.connection.value == ConnectionState.ONLINE }
        assertEquals(null, ana2.lastError.value)
        eventually("Chefe receber o Ola") { bodies(chefe, conv) == listOf("Ola") }
        assertEquals(0, ana2.store.queuedCount())
    }

    @Test
    fun `cenario do teste no celular - servidor reinstalado com arquivos e ola na fila`() = runBlocking {
        val (chefe, ana, conv) = family()
        val samePort = port
        // Servidor reinstalado (fora do ar) e Ana sem "ponte".
        server.stop()
        eventually("os dois caírem") {
            chefe.connection.value != ConnectionState.ONLINE && ana.connection.value != ConnectionState.ONLINE
        }
        val audio = randomFile("a.m4a", 300_000, 9)
        val pdf = randomFile("b.pdf", 9 * 1024 * 1024, 10) // 2 pedaços
        chefe.sendMedia(conv, audio, "audio", "audio/mp4", durationMs = 5_000)
        chefe.sendMedia(conv, pdf, "file", "application/pdf")
        ana.send(conv, "Ola")
        assertEquals(2, chefe.store.queuedCount())
        assertEquals(1, ana.store.queuedCount())

        startServer(samePort)
        listOf(chefe, ana).forEach { it.reconnectNow() }
        eventually("Ana receber os dois arquivos") { ana.store.messages(conv).count { it.attachment != null } == 2 }
        eventually("Chefe receber o Ola") { bodies(chefe, conv).contains("Ola") }
        eventually("filas vazias") { chefe.store.queuedCount() == 0 && ana.store.queuedCount() == 0 }
        val recebido = ana.store.messages(conv).first { it.attachment?.kind == "file" }.attachment!!
        kotlin.test.assertContentEquals(pdf.readBytes(), ana.mediaFile(recebido).readBytes())
    }

    @Test
    fun `botao reconectar refaz a conexao mesmo online e entrega o que estava na fila`() = runBlocking {
        val (chefe, ana, conv) = family()
        chefe.send(conv, "antes")
        eventually("Ana receber") { bodies(ana, conv) == listOf("antes") }
        val conexoesAntes = server.recentEvents().count { it.contains("Conectado") }
        chefe.reconnectNow(force = true)
        // A reconexão é tão rápida que nem dá para ver o "offline": confere pelo servidor.
        eventually("Chefe reconectar") { server.recentEvents().count { it.contains("Conectado") } > conexoesAntes }
        eventually("Chefe voltar") { chefe.connection.value == ConnectionState.ONLINE }
        chefe.send(conv, "depois")
        eventually("Ana receber depois") { bodies(ana, conv) == listOf("antes", "depois") }
        eventually("servidor ver 2 aparelhos") { server.hub.connectionCount() == 2 }
        assertEquals(2, server.hub.connectedNames().size)
        assertTrue(server.recentEvents().any { it.contains("Conectado") })
    }

    @Test
    fun `miniatura vai junto e quem recebe ve a previa antes de baixar`() = runBlocking {
        val (chefe, ana, conv) = family()
        val foto = randomFile("foto.jpg", 50_000, 21)
        val thumb = java.util.Base64.getEncoder().encodeToString(ByteArray(3000) { it.toByte() })
        chefe.sendMedia(conv, foto, "image", "image/jpeg", thumb = thumb)
        val video = randomFile("v.mp4", 60_000, 22)
        chefe.sendMedia(conv, video, "video", "video/mp4", durationMs = 15_000, thumb = "nao e base64!!")
        val grande = "A".repeat(app.falai.protocol.MAX_THUMB_CHARS + 4)
        chefe.sendMedia(conv, randomFile("g.png", 1000, 23), "image", "image/png", thumb = grande)
        eventually("Ana receber os 3") { ana.store.messages(conv).count { it.attachment != null } == 3 }
        val recebidos = ana.store.messages(conv).mapNotNull { it.attachment }
        assertEquals(thumb, recebidos.first { it.name == "foto.jpg" }.thumb)
        assertEquals(null, recebidos.first { it.name == "v.mp4" }.thumb) // inválida: descartada
        assertEquals(null, recebidos.first { it.name == "g.png" }.thumb) // grande demais: descartada
        // A prévia já está lá, mas o arquivo em si ainda não foi baixado.
        assertTrue(ana.store.messages(conv).all { it.localPath == null })
    }

    @Test
    fun `tipo do arquivo aparece pela extensao ou pelo mime`() {
        assertEquals("APK", ClientStore.typeLabel("Oxy Proxy Manager_1.0.4.apk", "application/vnd.android.package-archive"))
        assertEquals("APK", ClientStore.typeLabel("arquivo", "application/vnd.android.package-archive"))
        assertEquals("JPG", ClientStore.typeLabel("IMG_2026.jpeg", "image/jpeg"))
        assertEquals("JPG", ClientStore.typeLabel("1000012345", "image/jpeg"))
        assertEquals("PDF", ClientStore.typeLabel("boleto", "application/pdf"))
        assertEquals("MOV", ClientStore.typeLabel("video", "video/quicktime"))
        assertEquals("HEIC", ClientStore.typeLabel("foto", "image/heic"))
        assertEquals("ARQUIVO", ClientStore.typeLabel("sem nome", "application/octet-stream"))
        assertEquals("MP4", ClientStore.typeLabel("x.mp4", "video/mp4"))
    }

    @Test
    fun `resposta do pareamento se perde no 4G e o app tenta de novo sem gastar o convite`() = runBlocking {
        val (chefe, _, _) = family()
        val code = chefe.createInvite(forUserId = chefe.account.value!!.userId).code
        // "Ponte" que entrega o primeiro pedido ao servidor mas joga fora a resposta (como no 4G).
        val ponte = java.net.ServerSocket(0)
        var conexoes = 0
        val t = Thread {
            while (!ponte.isClosed) {
                val entrada = try { ponte.accept() } catch (e: Exception) { break }
                conexoes++
                val primeira = conexoes == 1
                Thread {
                    entrada.use { e ->
                        java.net.Socket("127.0.0.1", port).use { srv ->
                            val ida = Thread { runCatching { e.getInputStream().copyTo(srv.getOutputStream()) } }.apply { start() }
                            if (primeira) {
                                // Espera o servidor responder e derruba sem repassar.
                                runCatching { srv.getInputStream().read(ByteArray(4096)) }
                            } else {
                                runCatching { srv.getInputStream().copyTo(e.getOutputStream()) }
                            }
                            ida.interrupt()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        try {
            val pessoal = newClient("pessoal")
            val acc = pessoal.pair("http://127.0.0.1:${ponte.localPort}", code, "Celular pessoal", null)
            assertTrue(conexoes >= 2, "devia ter tentado de novo")
            assertEquals(chefe.account.value!!.userId, acc.userId)
            assertTrue(acc.isAdmin)
        } finally {
            ponte.close()
            t.interrupt()
        }
    }

    @Test
    fun `admin deixa o app disponivel para download so quando e mais novo`() = runBlocking {
        val (chefe, ana, _) = family()
        val apk = File(dir, "app.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B) + ByteArray(100_000) { 7 }) }
        val v1 = chefe.publishAppIfNewer(apk, 6, "0.4.0")
        assertEquals(6, v1?.versionCode)
        // Mesma versão: não envia de novo (o arquivo no servidor não muda).
        val outro = File(dir, "outro.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 1, 2, 3)) }
        assertEquals(100_002L, chefe.publishAppIfNewer(outro, 6, "0.4.0")?.size)
        // Quem não é admin não envia.
        assertEquals(null, ana.publishAppIfNewer(outro, 9, "9"))
        // Versão nova: envia.
        assertEquals(5L, chefe.publishAppIfNewer(outro, 7, "0.4.1")?.size)
    }

    @Test
    fun `admin salva o endereco da internet e os outros aparelhos ficam sabendo`() = runBlocking {
        val (chefe, ana, _) = family()
        assertEquals(null, chefe.publicBase.value)
        assertEquals("https://falai.falaifamilia.net", chefe.setPublicAddress("falai.falaifamilia.net"))
        assertEquals("https://falai.falaifamilia.net", chefe.publicBase.value)
        val erro = runCatching { ana.setPublicAddress("falai.outro.net") }.exceptionOrNull()
        assertTrue(erro is FalaiClientException && erro.code == "forbidden", "$erro")
        ana.reconnectNow(force = true)
        eventually("Ana saber o endereço") { ana.publicBase.value == "https://falai.falaifamilia.net" }
        assertEquals("https://falai.falaifamilia.net/convite/", chefe.createInvite().link!!.substringBeforeLast('/') + "/")
    }

    @Test
    fun `perfil - trocar nome e foto e a familia ve na hora`() = runBlocking {
        val (chefe, ana, conv) = family()
        val chefeId = chefe.account.value!!.userId
        val foto = randomFile("eu.jpg", 40_000, 31)
        val u = chefe.updateProfile(name = "  Edson  ", photo = foto)
        assertEquals("Edson", u.name)
        val avatar = assertNotNull(u.avatarMediaId)
        // Ana recebe a mudança sem precisar reconectar.
        eventually("Ana ver o nome novo") { ana.store.userName(chefeId) == "Edson" }
        eventually("Ana ver a foto") { ana.store.user(chefeId)?.avatarMediaId == avatar }
        assertEquals(avatar, ana.store.conversationSummaries(ana.account.value!!.userId).first { it.id == conv }.avatarMediaId)
        // E consegue baixar a foto, idêntica.
        kotlin.test.assertContentEquals(foto.readBytes(), ana.avatarFile(avatar).readBytes())
        // Nome vazio não pode.
        val vazio = runCatching { chefe.updateProfile(name = "   ") }.exceptionOrNull()
        assertTrue(vazio is FalaiClientException && vazio.code == "invalid", "$vazio")
        // Tirar a foto.
        assertEquals(null, chefe.updateProfile(removePhoto = true).avatarMediaId)
        eventually("Ana ver sem foto") { ana.store.user(chefeId)?.avatarMediaId == null }
        // Outro aparelho que entra depois já vê o nome certo.
        assertEquals("Edson", chefe.store.userName(chefeId))
    }
}
