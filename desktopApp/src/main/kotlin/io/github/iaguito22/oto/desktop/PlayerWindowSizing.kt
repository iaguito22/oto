package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.desktop.ui.PlayerForm
import kotlin.math.roundToInt

internal val PlayerForm.minimumSize: Pair<Int, Int>
    get() = when (this) {
        PlayerForm.Full -> 260 to 370
        PlayerForm.Horizontal -> 280 to 108
        PlayerForm.Vertical -> 96 to 240
    }

/** Tamaño al aparecer: el usuario puede redimensionarlo después. */
internal fun PlayerForm.openingSize(savedWidth: Int = width, savedHeight: Int = height): Pair<Int, Int> = when (this) {
    PlayerForm.Full -> savedWidth.coerceIn(260, 900).let { it to (it * 16f / 9f).roundToInt() }
    PlayerForm.Horizontal -> savedWidth.coerceIn(280, 2400) to 108
    PlayerForm.Vertical -> 96 to savedHeight.coerceIn(240, 1600)
}

/** También normaliza distribuciones guardadas, sin obligar a mantener el tamaño mientras se usan. */
internal fun DockLayout.preparePlayerOpening(form: PlayerForm) {
    minimums[DockId.Player] = form.minimumSize
    defaults[DockId.Player] = form.width
    val original = bounds[DockId.Player] ?: return
    val initialGroup = group(DockId.Player)
    // La altura es compartida al acoplar: el horizontal necesita abrirse independiente.
    // Una maximización guardada tampoco puede respetar los tamaños iniciales compactos.
    if ((form == PlayerForm.Horizontal && initialGroup.members.size > 1) || initialGroup.maximized) {
        val normal = initialGroup.restored[DockId.Player] ?: original
        close(DockId.Player)
        create(DockId.Player, normal)
    }
    val current = bounds.getValue(DockId.Player)
    val joined = group(DockId.Player).members.size > 1
    val size = if (form == PlayerForm.Full && joined) {
        (current.height * 9f / 16f).roundToInt().coerceAtLeast(form.minimumSize.first) to current.height
    } else form.openingSize(current.width, current.height)
    resize(DockId.Player, size.first, size.second)
}
