package io.github.iaguito22.oto.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.R

// Rubik variable (la fuente del shell de Caelestia). El japonés cae en la Noto CJK del sistema.
private fun rubik(weight: Int) = Font(
    R.font.rubik,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700), rubik(800))

object OtoType {
    val body = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.07.em)
    val rowTitle = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
    val rowSub = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp)
    /** Los títulos de sección (Tus listas, los carruseles): más finos que [title], para pesar menos que las carátulas. */
    val section = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp)
    val display = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp)
    val lyric = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.4).sp)
}
