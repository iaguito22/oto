package io.github.iaguito22.oto.desktop

import androidx.compose.ui.geometry.Offset

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class SingleWindowFrameTest {
    private interface WindowRegions : StdCallLibrary {
        fun GetWindowRgnBox(hwnd: WinDef.HWND, rect: WinDef.RECT): Int
    }

    @Test
    fun miniCoverDistinguishesClickSwipeAndCancelledDrag() {
        assertEquals(MiniCoverAction.Toggle, miniCoverAction(Offset(2f, -1f), moved = false, threshold = 16f))
        assertEquals(MiniCoverAction.Next, miniCoverAction(Offset(-25f, 3f), moved = true, threshold = 16f))
        assertEquals(MiniCoverAction.Previous, miniCoverAction(Offset(24f, -3f), moved = true, threshold = 16f))
        assertEquals(MiniCoverAction.None, miniCoverAction(Offset(3f, 1f), moved = true, threshold = 16f))
        assertEquals(MiniCoverAction.None, miniCoverAction(Offset(18f, 24f), moved = true, threshold = 16f))
    }

    @Test
    fun taskbarThumbnailRegistersAndRoutesPlaybackCommands() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val nativeSize = if (Native.POINTER_SIZE == 8) 552 else 540
        val buttons = thumbnailButtons(playing = true, flags = 1)
        val flagsOffset = if (Native.POINTER_SIZE == 8) 544L else 536L
        for ((index, button) in buttons.withIndex()) {
            assertEquals(13, button.pointer.getInt(0))
            assertEquals(TASKBAR_PREVIOUS + index, button.pointer.getInt(4))
            assertEquals(listOf(0, 2, 3)[index], button.pointer.getInt(8))
            assertEquals(1, button.pointer.getInt(flagsOffset))
        }
        assertEquals(1, thumbnailButtons(playing = false, flags = 0)[1].pointer.getInt(8))
        assertEquals(nativeSize, buttons[0].size())
        assertEquals(nativeSize.toLong(), Pointer.nativeValue(buttons[1].pointer) - Pointer.nativeValue(buttons[0].pointer))
        val frame = Frame("Oto taskbar test")
        val clicks = mutableListOf<String>()
        var hasSong = false
        var playing = false
        val toolbar = PlaybackThumbnail(frame) {
            TaskbarPlayback(hasSong, playing, { clicks.add("previous") }, { clicks.add("toggle") }, { clicks.add("next") })
        }
        try {
            EventQueue.invokeAndWait {
                frame.setSize(240, 140)
                frame.setAutoRequestFocus(false)
                frame.isVisible = true
            }
            val hwnd = WinDef.HWND(Native.getWindowPointer(frame))
            var registered = false
            repeat(15) {
                if (!registered) {
                    EventQueue.invokeAndWait { registered = toolbar.refresh() }
                    if (!registered) Thread.sleep(100)
                }
            }
            assertTrue("Explorer accepted the thumbnail toolbar", registered)
            fun click(id: Int) {
                val command = (0x1800L shl 16) or id.toLong()
                User32.INSTANCE.SendMessage(hwnd, 0x0111, WinDef.WPARAM(command), WinDef.LPARAM(0))
                EventQueue.invokeAndWait { }
            }
            click(TASKBAR_TOGGLE)
            assertTrue(clicks.isEmpty())
            EventQueue.invokeAndWait { hasSong = true; assertTrue(toolbar.refresh()) }
            click(TASKBAR_PREVIOUS)
            click(TASKBAR_TOGGLE)
            EventQueue.invokeAndWait { playing = true; assertTrue(toolbar.refresh()) }
            click(TASKBAR_NEXT)
            assertEquals(listOf("previous", "toggle", "next"), clicks)
        } finally {
            EventQueue.invokeAndWait { toolbar.close(); frame.dispose() }
        }
    }

    @Test
    fun windowsKeepsNativeCaptionDragResizeAndWorkArea() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val frame = Frame("Oto frame test")
        val zones = SingleCaptionZones().apply { set(0, DockRect(40, 20, 100, 35)) }
        try {
            EventQueue.invokeAndWait {
                frame.isUndecorated = true
                frame.setSize(500, 320)
                frame.setLocationRelativeTo(null)
                frame.setAutoRequestFocus(false)
                frame.isVisible = true
                assertTrue(enableSingleWindowFrame(frame, zones))
            }
            val hwnd = WinDef.HWND(Native.getWindowPointer(frame))
            val user = User32.INSTANCE
            val normal = WinDef.RECT()
            assertTrue(user.GetWindowRect(hwnd, normal))
            fun hit(x: Int, y: Int): Int {
                val packed = ((y and 0xffff) shl 16) or (x and 0xffff)
                return user.SendMessage(hwnd, 0x0084, WinDef.WPARAM(0), WinDef.LPARAM(packed.toLong())).toInt()
            }
            assertEquals(2, hit(normal.left + 70, normal.top + 30))
            zones.suspended = true
            assertEquals(1, hit(normal.left + 70, normal.top + 30))
            zones.suspended = false
            assertEquals(1, hit(normal.left + 70, normal.top + 70))
            assertEquals(10, hit(normal.left + 1, normal.top + 70))
            assertEquals(17, hit(normal.right - 1, normal.bottom - 1))

            val regions = Native.load("user32", WindowRegions::class.java)
            fun expectRegion(expected: Int, width: Int? = null) {
                val rect = WinDef.RECT()
                fun matches() = regions.GetWindowRgnBox(hwnd, rect) == expected && (width == null || rect.right - rect.left in width..(width + 1))
                repeat(50) {
                    if (matches()) return
                    Thread.sleep(20)
                }
                val actual = regions.GetWindowRgnBox(hwnd, rect)
                assertTrue("Expected region $expected width $width, got $actual with bounds $rect", matches())
            }
            EventQueue.invokeAndWait { setSingleWindowRounded(frame, 28) }
            expectRegion(3) // COMPLEXREGION
            assertEquals(10, hit(normal.left + 1, normal.top + 70))
            assertEquals(13, hit(normal.left + 8, normal.top + 8))
            assertEquals(1, hit(normal.left + 16, normal.top + 16))
            assertEquals(1, hit(normal.left + 12, normal.top + 70))

            // Al arrastrar, el tamaño nuevo debe poder verse completo hasta que se suelte.
            normal.write()
            user.SendMessage(hwnd, 0x0214, WinDef.WPARAM(2),
                WinDef.LPARAM(Pointer.nativeValue(normal.pointer))) // WM_SIZING, WMSZ_RIGHT
            expectRegion(0)
            val originalSize = frame.size
            EventQueue.invokeAndWait {
                frame.setSize(originalSize.width + 100, originalSize.height)
                setSingleWindowRounded(frame, 28)
            }
            val wider = WinDef.RECT()
            assertTrue(user.GetWindowRect(hwnd, wider))
            assertTrue(wider.right - wider.left > normal.right - normal.left)
            expectRegion(0)
            user.SendMessage(hwnd, 0x0232, WinDef.WPARAM(0), WinDef.LPARAM(0)) // WM_EXITSIZEMOVE
            expectRegion(3, wider.right - wider.left)
            EventQueue.invokeAndWait { frame.size = originalSize; setSingleWindowRounded(frame, null) }
            expectRegion(0)

            val expanded = CountDownLatch(1)
            zones.fixedHeight = true
            zones.onMiniDoubleClick = { expanded.countDown() }
            assertEquals(1, hit(normal.left + 70, normal.top + 1))
            assertEquals(11, hit(normal.right - 1, normal.bottom - 1))
            user.SendMessage(hwnd, 0x00A3, WinDef.WPARAM(2), WinDef.LPARAM(0)) // WM_NCLBUTTONDBLCLK, HTCAPTION
            assertTrue(expanded.await(2, TimeUnit.SECONDS))
            zones.onMiniDoubleClick = null
            assertEquals(10, hit(normal.left + 1, normal.top + 70))
            assertEquals(1, hit(normal.left + 70, normal.top + 1))
            zones.fixedHeight = false

            val monitor = user.MonitorFromWindow(hwnd, 2)
            val info = WinUser.MONITORINFO()
            assertTrue(user.GetMonitorInfo(monitor, info).booleanValue())
            user.SendMessage(hwnd, 0x0112, WinDef.WPARAM(0xF030), WinDef.LPARAM(0)) // SC_MAXIMIZE
            val client = WinDef.RECT()
            assertTrue(isZoomed(frame))
            assertTrue(user.GetClientRect(hwnd, client))
            assertEquals(info.rcWork.right - info.rcWork.left, client.right)
            assertEquals(info.rcWork.bottom - info.rcWork.top, client.bottom)

            user.SendMessage(hwnd, 0x0112, WinDef.WPARAM(0xF120), WinDef.LPARAM(0)) // SC_RESTORE
            val restored = WinDef.RECT()
            assertTrue(user.GetWindowRect(hwnd, restored))
            assertEquals(listOf(normal.left, normal.top, normal.right, normal.bottom),
                listOf(restored.left, restored.top, restored.right, restored.bottom))
        } finally {
            EventQueue.invokeAndWait { frame.dispose() }
        }
    }
}
