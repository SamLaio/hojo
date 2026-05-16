package wtf.anurag.hojo.ui.apps.fontconverter

data class FontCharacterSet(
        val id: String,
        val assetFileName: String? = null,
        val ranges: List<IntRange> = emptyList(),
        val characters: String = ""
) {
    fun codePoints(): Sequence<Int> =
            sequence {
                ranges.forEach { range ->
                    for (codePoint in range) yield(codePoint)
                }
                var index = 0
                while (index < characters.length) {
                    val codePoint = Character.codePointAt(characters, index)
                    yield(codePoint)
                    index += Character.charCount(codePoint)
                }
            }
}

object FontCharacterSets {
    val options =
            listOf(
                    FontCharacterSet(
                            id = "tc_common",
                            assetFileName = "fontsets/tc_common_moe_4808.txt"
                    ),
                    FontCharacterSet(
                            id = "tc_less_common",
                            assetFileName = "fontsets/tc_less_common_moe_6343.txt"
                    ),
                    FontCharacterSet(
                            id = "japanese",
                            ranges =
                                    listOf(
                                            0x3040..0x309F,
                                            0x30A0..0x30FF,
                                            0x31F0..0x31FF,
                                            0xFF65..0xFF9F
                                    )
                    ),
                    FontCharacterSet(
                            id = "sc",
                            ranges = listOf(0x3400..0x4DBF, 0x4E00..0x9FFF)
                    ),
                    FontCharacterSet(
                            id = "bopomofo",
                            ranges = listOf(0x3100..0x312F, 0x31A0..0x31BF)
                    ),
                    FontCharacterSet(
                            id = "latin",
                            ranges = listOf(0x0020..0x007E)
                    ),
                    FontCharacterSet(
                            id = "latin_extended",
                            ranges = listOf(0x00A0..0x00FF, 0x0100..0x017F)
                    ),
                    FontCharacterSet(
                            id = "hangul",
                            ranges = listOf(0x1100..0x11FF, 0x3130..0x318F, 0xAC00..0xD7AF)
                    ),
                    FontCharacterSet(
                            id = "greek_cyrillic",
                            ranges = listOf(0x0370..0x03FF, 0x0400..0x04FF)
                    ),
                    FontCharacterSet(
                            id = "symbols",
                            ranges = listOf(0x2190..0x21FF, 0x2200..0x22FF, 0x25A0..0x25FF)
                    )
            )

    val defaultSelectedIds = setOf("tc_common")

    private val requiredCharacters =
            """
            0123456789
            !#$%&'()*+,-./:;<=>?@[\]^_`{|}~
            ！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～
            　、。，．·・：；？！︰…‥﹐﹑﹒﹔﹕﹖﹗
            「」『』《》〈〉（ ）［ ］〔 〕【 】〖 〗〝〞
            “ ” ‘ ’‚ „ ‹ › « »
            —–―‐‑‒﹘﹣－─━ー
            ︐︑︒︓︔︕︖︗︘︙︱︲︳︴︵︶︷︸︹︺︻︼︽︾︿﹀﹁﹂﹃﹄﹙﹚﹛﹜﹝﹞
            ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ
            ℃％‰＋−×÷＝≠≦≧＜＞±
            """.trimIndent()

    fun buildCodePoints(
            selectedIds: Set<String>,
            loadAssetCharacters: (String) -> String = { "" }
    ): List<Int> {
        val selected = options.filter { it.id in selectedIds }
        return (selected.asSequence().flatMap { option ->
                    val assetCharacters =
                            option.assetFileName
                                    ?.let(loadAssetCharacters)
                                    .orEmpty()
                    option.codePoints() + stringCodePoints(assetCharacters)
                } + requiredCodePoints())
                .distinct()
                .sorted()
                .toList()
    }

    private fun requiredCodePoints(): Sequence<Int> =
            stringCodePoints(requiredCharacters).filter {
                !Character.isWhitespace(it) || it == 0x3000
            }

    private fun stringCodePoints(characters: String): Sequence<Int> =
            sequence {
                var index = 0
                while (index < characters.length) {
                    val codePoint = Character.codePointAt(characters, index)
                    yield(codePoint)
                    index += Character.charCount(codePoint)
                }
            }

    fun selectedLabel(
            selectedIds: Set<String>,
            labelForId: (String) -> String
    ): String {
        return options
                .filter { it.id in selectedIds }
                .joinToString("、") { labelForId(it.id) }
    }
}
