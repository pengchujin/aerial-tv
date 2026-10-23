package app.orchard.tv.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfRowsTest {
    private fun prog(
        title: String,
        intent: String? = "app://$title",
        channel: Long = 0,
        weight: Int = 0,
        engaged: Long = 0,
        position: Long = 0,
        duration: Long = 0,
        aspect: Int = -1,
        poster: String? = null,
        thumb: String? = null,
        episode: String? = null,
        description: String? = null,
        season: String? = null,
        episodeNo: String? = null,
    ) = RawProgram(
        title, episode, description, poster, thumb, aspect, intent, channel, weight, engaged, position, duration,
        season = season, episodeNumber = episodeNo,
    )

    @Test fun `watch next comes first, most recently engaged first`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("old", engaged = 1), prog("new", engaged = 9)),
            previews = listOf(prog("rec", channel = 1)),
            channelOrder = listOf(1),
        )
        assertEquals(listOf("new", "old", "rec"), items.map { it.title })
    }

    @Test fun `previews follow channel order, heavier first within a channel`() {
        val items = ShelfRows.arrange(
            watchNext = emptyList(),
            previews = listOf(
                prog("b-light", channel = 2, weight = 1),
                prog("a", channel = 1),
                prog("b-heavy", channel = 2, weight = 5),
            ),
            channelOrder = listOf(1, 2),
        )
        assertEquals(listOf("a", "b-heavy", "b-light"), items.map { it.title })
    }

    @Test fun `previews of hidden or unknown channels are left out`() {
        val items = ShelfRows.arrange(emptyList(), listOf(prog("x", channel = 7)), channelOrder = listOf(1))
        assertEquals(emptyList<ShelfItem>(), items)
    }

    @Test fun `programs that cannot be opened are left out`() {
        val items = ShelfRows.arrange(listOf(prog("no link", intent = null), prog("blank", intent = " ")), emptyList(), emptyList())
        assertEquals(emptyList<ShelfItem>(), items)
    }

    @Test fun `the same link appears once`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("ep", intent = "app://1")),
            previews = listOf(prog("ep again", intent = "app://1", channel = 1)),
            channelOrder = listOf(1),
        )
        assertEquals(listOf("ep"), items.map { it.title })
    }

    @Test fun `at most the limit`() {
        val many = (1..30).map { prog("p$it", channel = 1) }
        assertEquals(20, ShelfRows.arrange(emptyList(), many, listOf(1)).size)
    }

    @Test fun `progress only with a position inside a known duration`() {
        val items = ShelfRows.arrange(
            listOf(
                prog("half", position = 50, duration = 100, engaged = 3),
                prog("none", engaged = 2),
                prog("over", position = 150, duration = 100, engaged = 1),
            ),
            emptyList(), emptyList(),
        )
        assertEquals(0.5f, items[0].progress!!, 0.001f)
        assertNull(items[1].progress)
        assertNull(items[2].progress)
    }

    @Test fun `poster preferred over thumbnail, with its own aspect`() {
        val a = ShelfRows.arrange(listOf(prog("a", poster = "p", aspect = 4, thumb = "t")), emptyList(), emptyList()).single()
        assertEquals("p", a.image)
        assertEquals(2f / 3f, a.aspect, 0.001f)
        val b = ShelfRows.arrange(listOf(prog("b", thumb = "t")), emptyList(), emptyList()).single()
        assertEquals("t", b.image)
        assertEquals(16f / 9f, b.aspect, 0.001f)
    }

    @Test fun `subtitle is the episode title, else the description`() {
        val items = ShelfRows.arrange(
            listOf(prog("a", episode = "Ep 1", description = "d", engaged = 2), prog("b", description = "only d", engaged = 1)),
            emptyList(), emptyList(),
        )
        assertEquals("Ep 1", items[0].subtitle)
        assertEquals("d", items[0].description)
        assertEquals("only d", items[1].subtitle)
    }

    @Test fun `aspect codes follow TvContract`() {
        assertEquals(16f / 9f, ShelfRows.aspect(0), 0.001f)
        assertEquals(3f / 2f, ShelfRows.aspect(1), 0.001f)
        assertEquals(4f / 3f, ShelfRows.aspect(2), 0.001f)
        assertEquals(1f, ShelfRows.aspect(3), 0.001f)
        assertEquals(2f / 3f, ShelfRows.aspect(4), 0.001f)
        assertEquals(1f / 1.441f, ShelfRows.aspect(5), 0.001f)
        assertEquals(16f / 9f, ShelfRows.aspect(99), 0.001f)
    }

    @Test fun `items carry their channel and its title, watch next has none`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("w", engaged = 1)),
            previews = listOf(prog("p", channel = 4)),
            channelOrder = listOf(4),
            channelTitles = mapOf(4L to "Subscriptions"),
        )
        assertEquals(0L, items[0].channel)
        assertNull(items[0].channelTitle)
        assertEquals(4L, items[1].channel)
        assertEquals("Subscriptions", items[1].channelTitle)
    }

    @Test fun `tabs group the shelf by channel in shelf order`() {
        val items = ShelfRows.arrange(
            watchNext = listOf(prog("w", engaged = 1)),
            previews = listOf(prog("b", channel = 2), prog("a1", channel = 1), prog("a2", channel = 1, weight = -1)),
            channelOrder = listOf(1, 2),
            channelTitles = mapOf(1L to "A", 2L to "B"),
        )
        val tabs = ShelfRows.tabs(items)
        assertEquals(listOf(null, "A", "B"), tabs.map { it.title })
        assertEquals(listOf(listOf("w"), listOf("a1", "a2"), listOf("b")), tabs.map { t -> t.items.map { it.title } })
    }

    @Test fun `the limit is per tab, so a long channel does not crowd out the next`() {
        val items = ShelfRows.arrange(
            emptyList(),
            (1..30).map { prog("a$it", channel = 1) } + (1..5).map { prog("b$it", channel = 2) },
            listOf(1, 2),
        )
        assertEquals(listOf(20, 5), ShelfRows.tabs(items).map { it.items.size })
    }

    @Test fun `season and episode come from the display numbers, blank or zero meaning none`() {
        val items = ShelfRows.arrange(
            listOf(
                prog("ep", season = "1", episodeNo = "6", engaged = 3),
                prog("movie", engaged = 2),
                prog("zero", season = "0", episodeNo = " ", engaged = 1),
            ),
            emptyList(), emptyList(),
        )
        assertEquals("1", items[0].season)
        assertEquals("6", items[0].episode)
        assertNull(items[1].season)
        assertNull(items[1].episode)
        assertNull(items[2].season)
        assertNull(items[2].episode)
    }

    @Test fun `time left is the duration less the position, only where there is progress`() {
        val items = ShelfRows.arrange(
            listOf(
                prog("half", position = 600_000, duration = 3_600_000, engaged = 3),
                prog("none", engaged = 2),
                prog("broken", duration = 1, engaged = 1),
            ),
            emptyList(), emptyList(),
        )
        assertEquals(3_000_000L, items[0].leftMillis)
        assertNull(items[1].leftMillis)
        assertNull(items[2].leftMillis)
    }

    @Test fun `a blank description counts as none`() {
        val a = ShelfRows.arrange(emptyList(), listOf(prog("a", description = "  ", channel = 1)), listOf(1)).single()
        assertNull(a.description)
    }
}
