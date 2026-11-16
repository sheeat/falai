package app.falai.protocol

/**
 * Fundo das conversas: uma cor calma com desenhinhos bem clarinhos (corações, casinhas, balões,
 * estrelas...), como os mensageiros costumam ter. Os desenhos são nossos (traço simples em 24×24).
 * O app (Android) e a página web usam esta mesma lista, então o fundo fica igual nos dois.
 */
object Wallpapers {
    data class Color(val id: String, val name: String, val hex: String)

    /** Cores de fundo (todas claras e calmas). A primeira é a de fábrica. */
    val colors = listOf(
        Color("petroleo", "Azul-petróleo", "#E6EFEE"),
        Color("areia", "Areia", "#F2ECE3"),
        Color("menta", "Menta", "#E3F1E8"),
        Color("ceu", "Céu", "#E2ECF5"),
        Color("lavanda", "Lavanda", "#ECE9F4"),
        Color("pessego", "Pêssego", "#F6E8E1"),
        Color("cinza", "Cinza", "#ECEDEE"),
    )

    /** Cor dos desenhos: o azul-petróleo do Falaí, bem transparente (fica suave em qualquer cor). */
    const val DOODLE_RGB = "#136F7A"
    const val DOODLE_ALPHA = 0.11f
    const val STROKE = 1.5f

    /** Tamanho do "azulejo" que se repete, em pontos (dp no celular, px na web). */
    const val TILE = 280

    private const val HEART = "M12 20s-7-4.4-7-9.5A3.8 3.8 0 0 1 12 8a3.8 3.8 0 0 1 7 2.5C19 15.6 12 20 12 20z"
    private const val STAR = "M12 3l2.6 5.6 6 .6-4.5 4 1.3 6L12 16.3 6.6 19.2l1.3-6-4.5-4 6-.6z"
    private const val BUBBLE = "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H10l-4 3v-3H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"
    private const val HOUSE = "M4 11l8-7 8 7M6 9.5V20h12V9.5M10 20v-5h4v5"
    private const val NOTE = "M9 17V5l10-2v12M9 17a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0zM19 15a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0z"
    private const val SUN = "M12 8a4 4 0 1 1 0 8a4 4 0 0 1 0-8zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"
    private const val LEAF = "M5 19C5 10 11 5 20 4c-1 9-6 15-15 15zM5 19l8-8"
    private const val CUP = "M5 8h11v6a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 10h2a2 2 0 0 1 0 4h-2M8 3v2M12 3v2"
    private const val CAMERA = "M4 8h3l2-2h6l2 2h3v11H4zM12 10.5a3 3 0 1 1 0 6a3 3 0 0 1 0-6z"
    private const val SMILE = "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18zM8.5 14a4 4 0 0 0 7 0M9 9.5h.01M15 9.5h.01"
    private const val PLANE = "M3 11l18-8-7 18-3-7zM11 14l10-11"
    private const val CLOUD = "M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9a4.5 4.5 0 0 1 0 9z"
    private const val DOT = "M12 11a1 1 0 1 1 0 2a1 1 0 0 1 0-2z"

    /** Um desenho no azulejo: caminho (em 24×24), posição do canto, giro (graus) e tamanho. */
    data class Doodle(val path: String, val x: Float, val y: Float, val rotate: Float, val scale: Float)

    val doodles = listOf(
        Doodle(HEART, 14f, 12f, -12f, 1.1f),
        Doodle(BUBBLE, 92f, 30f, 8f, 1.15f),
        Doodle(STAR, 176f, 8f, 14f, 1.0f),
        Doodle(HOUSE, 230f, 64f, -6f, 1.05f),
        Doodle(NOTE, 40f, 84f, 10f, 1.0f),
        Doodle(SUN, 130f, 96f, 0f, 1.1f),
        Doodle(LEAF, 206f, 138f, -18f, 1.05f),
        Doodle(CUP, 10f, 160f, 6f, 1.05f),
        Doodle(SMILE, 82f, 168f, -8f, 1.0f),
        Doodle(CAMERA, 150f, 186f, 10f, 1.0f),
        Doodle(PLANE, 236f, 214f, -10f, 1.0f),
        Doodle(CLOUD, 44f, 234f, 0f, 1.1f),
        Doodle(HEART, 120f, 246f, 16f, 0.85f),
        Doodle(STAR, 196f, 252f, -10f, 0.75f),
        Doodle(DOT, 70f, 50f, 0f, 1f),
        Doodle(DOT, 182f, 70f, 0f, 1f),
        Doodle(DOT, 100f, 132f, 0f, 1f),
        Doodle(DOT, 252f, 170f, 0f, 1f),
        Doodle(DOT, 22f, 116f, 0f, 1f),
        Doodle(DOT, 168f, 152f, 0f, 1f),
    )

    fun colorById(id: String?): Color = colors.firstOrNull { it.id == id } ?: colors.first()

    /** O azulejo em SVG (para a página web). Só os desenhos, fundo transparente. */
    fun tileSvg(): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="$TILE" height="$TILE" viewBox="0 0 $TILE $TILE">""")
        append("""<g fill="none" stroke="$DOODLE_RGB" stroke-opacity="$DOODLE_ALPHA" stroke-width="$STROKE" stroke-linecap="round" stroke-linejoin="round">""")
        for (d in doodles) {
            append("""<path transform="translate(${d.x} ${d.y}) rotate(${d.rotate} 12 12) scale(${d.scale})" """)
            append("""vector-effect="non-scaling-stroke" d="${d.path}"/>""")
        }
        append("</g></svg>")
    }
}
