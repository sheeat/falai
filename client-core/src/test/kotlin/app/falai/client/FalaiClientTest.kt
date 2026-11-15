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
        server = FalaiServer(Store(JdbcDb(File(dir, "server.db").path)), ServerConfig(port = fixedPort), log = {})
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

    private fun newClient(name: String): FalaiClient {
        val c = FalaiClient(
            ClientStore(JdbcDb(File(dir, "$name.db").path)), scope,
            reconnectDelaysMs = listOf(100, 200, 300),
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
}
