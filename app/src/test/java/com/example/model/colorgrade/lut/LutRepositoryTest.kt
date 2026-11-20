package com.example.model.colorgrade.lut

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Verifies the persisted-LUT lifecycle: re-registration after restart and real deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LutRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun writeLutFile(id: String, content: String = "TITLE test\nLUT_3D_SIZE 2\n"): File {
        val directory = File(context.filesDir, "luts").apply { mkdirs() }
        return File(directory, "lut_$id.cube").apply { writeText(content) }
    }

    @Test
    fun initializeRegistersPersistedLutFilesAfterARestart() {
        val file = writeLutFile("persisted-1")
        file.parentFile?.listFiles()?.forEach { other ->
            if (other != file) other.delete()
        }

        kotlinx.coroutines.runBlocking {
            LutRepository.initialize(context)
            val entries = LutRepository.getLuts()

            val entry = entries.singleOrNull { it.id == "persisted-1" }
            assertNotNull("Persisted LUT file must be re-registered after restart", entry)
            assertEquals(file.absolutePath, entry?.path)
        }
    }

    @Test
    fun initializeIsIdempotentAndDoesNotDuplicateEntries() {
        writeLutFile("persisted-2")
        writeLutFile("persisted-3")

        kotlinx.coroutines.runBlocking {
            LutRepository.initialize(context)
            val firstCount = LutRepository.getLuts().size
            LutRepository.initialize(context)

            assertEquals(firstCount, LutRepository.getLuts().size)
        }
    }

    @Test
    fun deleteLutRemovesEntryCacheAndPersistedFile() {
        val file = writeLutFile("deletable")
        kotlinx.coroutines.runBlocking {
            LutRepository.initialize(context)

            val deleted = LutRepository.deleteLut("deletable")

            assertTrue(deleted)
            assertFalse(file.exists())
            assertTrue(LutRepository.getLuts().none { it.id == "deletable" })
            assertNull(LutRepository.getCachedData("deletable"))
        }
    }

    @Test
    fun deleteLutReturnsFalseForUnknownIds() {
        kotlinx.coroutines.runBlocking {
            assertFalse(LutRepository.deleteLut("does-not-exist"))
        }
    }
}
