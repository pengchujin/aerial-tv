package app.orchard.tv.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfAskTest {
    private fun r(pkg: String, stamp: Long = 1, cls: String = "$pkg.R") = ShelfAsk.Receiver(pkg, cls, stamp)

    @Test fun `every app is asked once`() {
        val rs = listOf(r("a"), r("b"))
        assertEquals(listOf("a", "b"), ShelfAsk.pending(rs, emptySet(), own = "me").map { it.pkg })
        val asked = ShelfAsk.pending(rs, emptySet(), own = "me").map(ShelfAsk::key).toSet()
        assertEquals(emptyList<ShelfAsk.Receiver>(), ShelfAsk.pending(rs, asked, own = "me"))
    }

    @Test fun `an app that was updated is asked again`() {
        val asked = setOf(ShelfAsk.key(r("a", stamp = 1)))
        assertEquals(listOf("a"), ShelfAsk.pending(listOf(r("a", stamp = 2)), asked, own = "me").map { it.pkg })
    }

    @Test fun `aerial does not ask itself`() {
        assertEquals(emptyList<ShelfAsk.Receiver>(), ShelfAsk.pending(listOf(r("me")), emptySet(), own = "me"))
    }

    @Test fun `an app with several receivers is asked through each`() {
        val rs = listOf(r("a", cls = "a.One"), r("a", cls = "a.Two"))
        assertEquals(2, ShelfAsk.pending(rs, emptySet(), own = "me").size)
    }
}
