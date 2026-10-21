package com.lmreader.ui.video.player

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No media, model, network or Activity launch: these tests exercise the snapshot
 * store and the Intent/saved-state token contract directly. Files use fresh UUID
 * tokens inside the app's private no-backup directory and are removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaylistSnapshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun largePlaylistTravelsAsSmallTokenAndRestoresExactOrder() {
        val ids = ArrayList<String>(10_502)
        repeat(10_500) { ids.add(UUID.randomUUID().toString()) }
        ids.add(ids[17])
        ids.add(ids[9_999])
        val current = UUID.randomUUID().toString()
        val expected = VideoPlaylistSnapshots.deduplicate(ids, current)
        assertEquals(10_501, expected.size)
        assertEquals(current, expected.last())

        val intent = VideoPlayerActivity.openIntent(context, current, ids)
        assertNull(intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_PLAYLIST_IDS))
        val token = intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYLIST_TOKEN)
        assertTrue(VideoPlaylistSnapshots.isValidToken(token))
        assertEquals(current, intent.getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_ID))

        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            assertTrue(
                "large playlist Intent must stay below 128 KiB, was ${parcel.dataSize()}",
                parcel.dataSize() < 128 * 1024,
            )
        } finally {
            parcel.recycle()
        }

        try {
            // A fresh read simulates process recreation: no in-memory list is used.
            assertEquals(expected, VideoPlaylistSnapshots.load(context, token))
            assertEquals(expected, VideoPlaylistSnapshots.load(context, token))

            // The Activity saves only this token in its instance-state Bundle.
            val saved = Bundle().apply {
                putString(VideoPlayerActivity.STATE_PLAYLIST_TOKEN, token)
            }
            val restoredToken = saved.getString(VideoPlayerActivity.STATE_PLAYLIST_TOKEN)
            assertEquals(expected, VideoPlaylistSnapshots.load(context, restoredToken))
        } finally {
            VideoPlaylistSnapshots.delete(context, token!!)
        }
    }

    @Test
    fun smallPlaylistStillUsesInlineIntentExtras() {
        val ids = (0 until 40).map { UUID.randomUUID().toString() }
        val current = ids[7]
        val intent = VideoPlayerActivity.openIntent(context, current, ids)
        assertEquals(ids, intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_PLAYLIST_IDS))
        assertNull(intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYLIST_TOKEN))
    }

    @Test
    fun corruptManifestAndMissingFileFailClosed() {
        val ids = (0 until 300).map { UUID.randomUUID().toString() }
        val token = VideoPlaylistSnapshots.persistBlocking(context, ids)
        val file = VideoPlaylistSnapshots.snapshotFile(context, token)
        try {
            assertEquals(ids, VideoPlaylistSnapshots.load(context, token))

            file.writeText("not-a-valid-snapshot")
            assertNull(VideoPlaylistSnapshots.load(context, token))

            // Valid shape, wrong hash: still rejected as a whole.
            file.writeText(
                "LMREADER-VIDEO-PLAYLIST\n1\n${ids.size}\n" +
                    "0".repeat(64) + "\n" + ids.joinToString("\n"),
            )
            assertNull(VideoPlaylistSnapshots.load(context, token))

            file.delete()
            assertNull(VideoPlaylistSnapshots.load(context, token))
        } finally {
            file.delete()
        }
    }

    @Test
    fun tokenPathOnlyAcceptsPrivateUuidFileNames() {
        assertNull(VideoPlaylistSnapshots.load(context, "../outside"))
        assertNull(VideoPlaylistSnapshots.load(context, UUID.randomUUID().toString()))
        try {
            VideoPlaylistSnapshots.snapshotFile(context, "../../escape")
            fail("non-UUID token must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertNotNull(VideoPlaylistSnapshots.playlistsDir(context))
    }
}