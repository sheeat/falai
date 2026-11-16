package app.falai.server

import app.falai.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaTest {
    private lateinit var dir: File
    private lateinit var store: Store
    private lateinit var server: FalaiServer
    private lateinit var http: HttpClient
    private var port = 0
    private lateinit var admin: AuthedDevice
    private lateinit var adminToken: String
    private lateinit var anaToken: String
    private lateinit var brunoToken: String
    private lateinit var conv: ConversationDto

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-media").toFile()
        store = Store(JdbcDb(File(dir, "t.db").path))
        server = FalaiServer(store, ServerConfig(port = 0, mediaDir = File(dir, "media")), log = {})
        val code = server.start()!!
        port = server.boundPort()
        http = HttpClient(CIO) { expectSuccess = false }

        val a = store.pair(PairRequest(code, "cel", "Chefe"))
        adminToken = a.token
        admin = store.authenticate(a.token)!!
        val ana = store.pair(PairRequest(store.createInvite(admin.user, null).first, "cel", "Ana"))
        anaToken = ana.token
        brunoToken = store.pair(PairRequest(store.createInvite(admin.user, null).first, "cel", "Bruno")).token
        conv = store.createConversation(admin.user, "direct", null, listOf(ana.userId))
    }

    @AfterTest
    fun tearDown() {
        http.close()
        server.stop()
        dir.deleteRecursively()
    }

    private val base get() = "http://127.0.0.1:$port"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun status(r: String) = FalaiJson.decodeFromString(MediaStatus.serializer(), r)

    private suspend fun create(token: String, data: ByteArray, name: String = "foto.jpg"): MediaStatus {
        val r = http.post("$base/api/media") {
            header("Authorization", "Bearer $token")
            setBody(FalaiJson.encodeToString(MediaCreateRequest.serializer(), MediaCreateRequest(data.size.toLong(), sha(data), "image/jpeg", name)))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return status(r.bodyAsText())
    }

    private suspend fun putChunk(token: String, id: String, offset: Long, bytes: ByteArray): HttpResponse =
        http.put("$base/api/media/$id?offset=$offset") {
            header("Authorization", "Bearer $token")
            setBody(bytes)
        }

    private suspend fun uploadAll(token: String, data: ByteArray): MediaStatus {
        val st = create(token, data)
        var off = 0
        var last = st
        while (off < data.size) {
            val end = minOf(off + MEDIA_CHUNK_BYTES, data.size)
            val r = putChunk(token, st.mediaId, off.toLong(), data.copyOfRange(off, end))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            last = status(r.bodyAsText())
            off = end
        }
        return last
    }

    private suspend fun download(token: String, id: String) = http.get("$base/api/media/$id") { header("Authorization", "Bearer $token") }

    @Test
    fun `arquivo grande sobe em pedacos e desce identico`() = runBlocking {
        val data = Random(1).nextBytes(20 * 1024 * 1024 + 123) // 3 pedaços
        val st = uploadAll(adminToken, data)
        assertTrue(st.complete)
        val r = download(adminToken, st.mediaId)
        assertEquals(HttpStatusCode.OK, r.status)
        assertContentEquals(data, r.bodyAsBytes())
        assertEquals(sha(data), r.headers["X-Content-SHA256"])
    }

    @Test
    fun `envio interrompido retoma de onde parou`() = runBlocking {
        val data = Random(2).nextBytes(10 * 1024 * 1024)
        val st = create(adminToken, data)
        assertEquals(HttpStatusCode.OK, putChunk(adminToken, st.mediaId, 0, data.copyOfRange(0, MEDIA_CHUNK_BYTES)).status)

        // "Caiu a conexão": o app pergunta onde parou.
        val s = status(http.get("$base/api/media/${st.mediaId}/status") { header("Authorization", "Bearer $adminToken") }.bodyAsText())
        assertEquals(MEDIA_CHUNK_BYTES.toLong(), s.received)
        assertFalse(s.complete)

        // Pedaço fora de ordem é recusado.
        assertEquals(HttpStatusCode.Conflict, putChunk(adminToken, st.mediaId, 0, data.copyOfRange(0, 10)).status)

        val r = putChunk(adminToken, st.mediaId, s.received, data.copyOfRange(MEDIA_CHUNK_BYTES, data.size))
        assertTrue(status(r.bodyAsText()).complete)
        assertContentEquals(data, download(adminToken, st.mediaId).bodyAsBytes())
    }

    @Test
    fun `arquivo corrompido no caminho e recusado`() = runBlocking {
        val data = Random(3).nextBytes(1000)
        val st = create(adminToken, data)
        val bad = data.copyOf().also { it[500] = (it[500] + 1).toByte() }
        val r = putChunk(adminToken, st.mediaId, 0, bad)
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("hash_mismatch"))
        // Pode tentar de novo do zero.
        assertTrue(status(putChunk(adminToken, st.mediaId, 0, data).bodyAsText()).complete)
    }

    @Test
    fun `pedaco maior que 8 MB e recusado`() = runBlocking {
        val data = ByteArray(MEDIA_CHUNK_BYTES + 1)
        val st = create(adminToken, data)
        assertEquals(HttpStatusCode.PayloadTooLarge, putChunk(adminToken, st.mediaId, 0, data).status)
    }

    @Test
    fun `sem token nao sobe nem baixa e so quem participa baixa`() = runBlocking {
        val r = http.post("$base/api/media") { setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)

        val data = Random(4).nextBytes(5000)
        val st = uploadAll(adminToken, data)
        // Antes de ir para alguma conversa, só quem enviou baixa.
        assertEquals(HttpStatusCode.NotFound, download(anaToken, st.mediaId).status)
        // Outra pessoa não pode mexer no envio de alguém.
        assertEquals(HttpStatusCode.Forbidden, putChunk(anaToken, st.mediaId, 0, data).status)

        val att = AttachmentDto(st.mediaId, "image", "x", "x", 0, "x")
        val msg = store.storeMessage(admin, SendFrame(UUID.randomUUID().toString(), conv.id, "", att)).message
        // O servidor corrige os dados do anexo com o que ele mesmo conferiu.
        val saved = assertNotNull(msg.attachment)
        assertEquals(sha(data), saved.sha256)
        assertEquals(5000L, saved.size)
        assertEquals("image/jpeg", saved.mime)

        assertContentEquals(data, download(anaToken, st.mediaId).bodyAsBytes())   // Ana está na conversa
        assertEquals(HttpStatusCode.NotFound, download(brunoToken, st.mediaId).status) // Bruno não

        // O anexo volta igual no sync.
        val synced = store.messagesSince(store.authenticate(anaToken)!!.user.id, 0, 10).single()
        assertEquals(saved, synced.attachment)
    }

    @Test
    fun `mensagem nao aceita anexo incompleto ou de outra pessoa`() = runBlocking {
        val data = Random(5).nextBytes(3000)
        val st = create(adminToken, data)
        val att = AttachmentDto(st.mediaId, "image", "image/jpeg", "f.jpg", 3000, sha(data))
        val e1 = assertFailsWith<FalaiException> { store.storeMessage(admin, SendFrame("m1", conv.id, "", att)) }
        assertEquals("invalid", e1.code)

        putChunk(adminToken, st.mediaId, 0, data)
        val ana = store.authenticate(anaToken)!!
        val e2 = assertFailsWith<FalaiException> { store.storeMessage(ana, SendFrame("m2", conv.id, "", att)) }
        assertEquals("forbidden", e2.code)

        // Do jeito certo funciona, inclusive sem texto.
        assertEquals("", store.storeMessage(admin, SendFrame("m3", conv.id, "", att)).message.body)
    }

    @Test
    fun `banco antigo do M1 ganha as colunas novas sem perder nada`() {
        // Simula um banco criado pelo M1 (versão 1) com uma mensagem.
        val f = File(dir, "antigo.db")
        val db = JdbcDb(f.path)
        db.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("INSERT INTO meta VALUES ('schema_version', '1')")
        db.execute("CREATE TABLE users (id TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE devices (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER)")
        db.execute("CREATE TABLE invites (code_hash TEXT PRIMARY KEY, created_by TEXT, for_user_id TEXT, make_admin INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL, used_at INTEGER)")
        db.execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT, created_by TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE members (conversation_id TEXT NOT NULL, user_id TEXT NOT NULL, PRIMARY KEY (conversation_id, user_id))")
        db.execute("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation_id TEXT NOT NULL, author_id TEXT NOT NULL, author_device_id TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execute("INSERT INTO users VALUES ('u1', 'Chefe', 1, 0)")
        db.execute("INSERT INTO conversations VALUES ('c1', 'group', 'G', 'u1', 0)")
        db.execute("INSERT INTO members VALUES ('c1', 'u1')")
        db.execute("INSERT INTO messages (id, conversation_id, author_id, author_device_id, body, created_at) VALUES ('m1', 'c1', 'u1', 'd1', 'antiga', 0)")

        val s = Store(db)
        val m = s.messagesSince("u1", 0, 10).single()
        assertEquals("antiga", m.body)
        assertEquals(null, m.attachment)
    }
}
