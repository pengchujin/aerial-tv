package app.orchard.tv.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AlbumPhotos reads a shared album's page; the page here is shaped like the ones Google serves, made up. */
class AlbumPhotosTest {
    private val page = """
        <html><script>AF_initDataCallback({key: 'ds:1', data:[[["AF1Q","x"],
        ["https:\/\/lh3.googleusercontent.com\/pw\/AbCd-123_x",4032,3024,1700000000000],
        ["https:\/\/lh3.googleusercontent.com\/pw\/ZzZz987",3000,4000,1700000001000],
        ["https:\/\/lh3.googleusercontent.com\/pw\/AbCd-123_x",4032,3024,1700000000000],
        ["https:\/\/lh3.googleusercontent.com\/a\/AvatarOnly",96,96],
        ["https:\/\/lh3.googleusercontent.com\/pw\/Tiny",120,90]
        ]]})</script></html>
    """.trimIndent()

    @Test
    fun picturesAreTheLargeOnesUnderPwInPageOrderWithoutRepeats() {
        assertEquals(
            listOf("https://lh3.googleusercontent.com/pw/AbCd-123_x", "https://lh3.googleusercontent.com/pw/ZzZz987"),
            AlbumPhotos.pictures(page),
        )
    }

    @Test
    fun aPageWithoutPicturesGivesNone() {
        assertTrue(AlbumPhotos.pictures("<html>Sign in</html>").isEmpty())
    }

    @Test
    fun onlyGoogleAlbumLinksAreAccepted() {
        assertTrue(AlbumPhotos.isAlbumLink("https://photos.app.goo.gl/AbCdEf123"))
        assertTrue(AlbumPhotos.isAlbumLink(" https://photos.google.com/share/AF1Q?key=xyz "))
        assertFalse(AlbumPhotos.isAlbumLink("http://photos.app.goo.gl/AbCdEf123"))
        assertFalse(AlbumPhotos.isAlbumLink("https://evil.example/photos.app.goo.gl"))
        assertFalse(AlbumPhotos.isAlbumLink("https://photos.google.com/u/0/"))
        assertFalse(AlbumPhotos.isAlbumLink("not a link"))
    }
}
