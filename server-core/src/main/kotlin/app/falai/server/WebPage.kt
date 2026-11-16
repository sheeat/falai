package app.falai.server

/** A página "Falaí no computador" (HTML, CSS e JavaScript num arquivo só, sem nada de fora). */
val WEB_PAGE: String by lazy {
    FalaiServer::class.java.getResourceAsStream("/web/falai-web.html")!!.use { it.readBytes().decodeToString() }
}
