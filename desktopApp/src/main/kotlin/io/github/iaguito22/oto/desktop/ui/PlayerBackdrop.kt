package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.abs

/** Las mismas tres pasadas y velo que el fondo de la pantalla del móvil. */
internal suspend fun blurredBackdrop(image: ImageBitmap): ImageBitmap = withContext(Dispatchers.Default) {
    val input = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    image.readPixels((input.raster.dataBuffer as DataBufferInt).data)
    val scaled = BufferedImage(240, 240, BufferedImage.TYPE_INT_ARGB)
    scaled.createGraphics().let { g -> g.drawImage(input, 0, 0, 240, 240, null); g.dispose() }
    val pixels = (scaled.raster.dataBuffer as DataBufferInt).data
    val tmp = IntArray(pixels.size)
    fun box(src:IntArray,dst:IntArray,r:Int,horizontal:Boolean) {
        val span=2*r+1
        for(line in 0 until 240) {
            val base=if(horizontal)line*240 else line
            val step=if(horizontal)1 else 240
            fun at(i:Int)=src[base+i.coerceIn(0,239)*step]
            var a=0;var red=0;var green=0;var blue=0
            for(i in -r..r){val c=at(i);a+=c ushr 24;red+=(c shr 16) and 255;green+=(c shr 8) and 255;blue+=c and 255}
            for(i in 0 until 240) {
                val half=span/2
                dst[base+i*step]=((a+half)/span shl 24) or ((red+half)/span shl 16) or ((green+half)/span shl 8) or ((blue+half)/span)
                val out=at(i-r);val inn=at(i+r+1)
                a+=(inn ushr 24)-(out ushr 24);red+=((inn shr 16) and 255)-((out shr 16) and 255);green+=((inn shr 8) and 255)-((out shr 8) and 255);blue+=(inn and 255)-(out and 255)
            }
        }
    }
    for(r in intArrayOf(9,9,10)){box(pixels,tmp,r,true);box(tmp,pixels,r,false)}
    val info = ImageInfo(240, 240, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
    val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(pixels)
    SkiaImage.makeRaster(info, bytes.array(), 240 * Int.SIZE_BYTES).toComposeImageBitmap()
}

@Composable
internal fun PlayerBackdrop(image: ImageBitmap, opacity: Float = 1f, extended: Boolean = false) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.coerceIn(0f,1f) }) {
    Crossfade(image, animationSpec=tween(800),label="backdrop") { bmp ->
        val colors = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) })
        if (extended) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            val copies = ceil((maxWidth / maxHeight) / 2f).toInt()
            for (index in -copies..copies) {
                Image(bmp,null,Modifier.offset(x=maxHeight*index).size(maxHeight)
                    .graphicsLayer { scaleX=if(abs(index)%2 == 1) -1f else 1f },
                    contentScale=ContentScale.FillBounds,colorFilter=colors)
            }
        } else Image(bmp,null,Modifier.fillMaxSize().graphicsLayer { scaleX=1.35f;scaleY=1.35f },contentScale=ContentScale.Crop,colorFilter=colors)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C080808),.55f to Color(0x6B080808),1f to Color(0xCC080808))))
    }
}
