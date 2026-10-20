package com.beiwater.inkcompat

import java.nio.ByteOrder
import java.nio.ShortBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEntryTest {
    private val white = 0xFFFFFFFF.toInt()

    private fun geometry(): MeshGeometry {
        val vs = listOf(
            TestEncoder.Vertex(0f, 0f, 0f, u = 0f, v = 0f),
            TestEncoder.Vertex(1f, 0f, 0f, u = 1f, v = 0f),
            TestEncoder.Vertex(0f, 1f, -1f, u = 0f, v = 1f),
        )
        val vb = TestEncoder.unpackedVertices(vs, ByteOrder.nativeOrder())
        val ib = ShortBuffer.wrap(shortArrayOf(0, 1, 2))
        return MeshGeometryBuilder.fromUnpacked(vb, ib, 3)!!
    }

    @Test
    fun colorsAreCachedUntilKeyChanges() {
        val e = MeshEntry(geometry())
        val k1 = ColorKey(0xFF336699.toInt(), listOf(InkColorFunction.OpacityMultiplier(0.5f)))
        val c1 = e.colors(k1)
        assertSame(c1, e.colors(k1.copy()))
        assertEquals(1, e.colorComputations)

        // opacityShift 0 -> alpha 0.5；-1 -> alpha 0；RGB 保持笔刷基色
        assertEquals(0x80, c1[0] ushr 24)
        assertEquals(0x00, c1[2] ushr 24)
        assertEquals(0x336699, c1[0] and 0xFFFFFF)

        val c2 = e.colors(k1.copy(argb = 0xFFFF0000.toInt()))
        assertEquals(2, e.colorComputations)
        assertEquals(0xFF0000, c2[0] and 0xFFFFFF)
    }

    @Test
    fun noColorFunctionsGivesFullAlphaForZeroShift() {
        val e = MeshEntry(geometry())
        assertEquals(white, e.colors(ColorKey(white, emptyList()))[0])
    }

    @Test
    fun texCoordsAreTexelUnitsAndCached() {
        val e = MeshEntry(geometry())
        val key = TexKey(1, 1, 64, 32)
        val t = e.texCoords(key)
        assertEquals(6, t.size)
        assertEquals(0f, t[0], 1e-3f)
        assertEquals(64f, t[2], 0.05f)   // u=1 -> 位图宽
        assertEquals(32f, t[5], 0.05f)   // v=1 -> 位图高
        assertSame(t, e.texCoords(key.copy()))
        assertEquals(1, e.texComputations)

        val t2 = e.texCoords(key.copy(width = 128))
        assertEquals(2, e.texComputations)
        assertEquals(128f, t2[2], 0.1f)
    }

    @Test
    fun texGridDividesUvByRowsAndColumns() {
        val e = MeshEntry(geometry())
        val t = e.texCoords(TexKey(rows = 2, columns = 4, width = 40, height = 20))
        // frames == 1：只用第 0 格，u/4 * 40、v/2 * 20
        assertEquals(10f, t[2], 0.05f)
        assertEquals(10f, t[5], 0.05f)
    }

    @Test
    fun weakEntryCacheRemembersFailure() {
        val cache = WeakEntryCache<Any>()
        val key = Any()
        var calls = 0
        assertNull(cache.getOrPut(key) { calls++; null })
        assertNull(cache.getOrPut(key) { calls++; null })
        assertEquals(1, calls)
    }

    @Test
    fun weakEntryCacheReturnsSameEntryPerKey() {
        val cache = WeakEntryCache<Any>()
        val a = Any()
        val b = Any()
        val ea = cache.getOrPut(a) { geometry() }
        assertNotNull(ea)
        assertSame(ea, cache.getOrPut(a) { error("不应再创建") })
        assertTrue(cache.getOrPut(b) { geometry() } !== ea)
        assertEquals(2, cache.size())
    }

    @Test
    fun versionedCacheRebuildsOnVersionOrContextChange() {
        val cache = VersionedCache<Any, Any>()
        val key = Any()
        val ctx = Any()
        var builds = 0
        val v1 = cache.get(key, 1L, ctx) { builds++; Any() }
        assertSame(v1, cache.get(key, 1L, ctx) { builds++; Any() })
        assertEquals(1, builds)
        val v2 = cache.get(key, 2L, ctx) { builds++; Any() }
        assertTrue(v2 !== v1)
        assertEquals(2, builds)
        cache.get(key, 2L, Any()) { builds++; Any() }
        assertEquals(3, builds)
    }
}
