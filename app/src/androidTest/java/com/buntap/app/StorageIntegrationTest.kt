package com.buntap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buntap.app.automation.SessionRecord
import com.buntap.app.data.TargetImageStore
import com.buntap.app.data.db.BunTapDatabase
import com.buntap.app.data.repository.ClickerRepository
import com.buntap.app.data.repository.HistoryRepository
import com.buntap.app.data.repository.RoutineLibrary
import com.buntap.app.data.repository.RoutineRepository
import com.buntap.app.data.repository.TargetRepository
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ExecutionEvent
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.routines.ImportResult
import com.buntap.app.routines.RoutineBody
import com.buntap.app.routines.TapTargetAction
import com.buntap.app.routines.WaitAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Room + file storage + import/export, against a real (in-memory) database on a device. */
@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: BunTapDatabase
    private lateinit var dir: File
    private lateinit var images: TargetImageStore
    private lateinit var targets: TargetRepository
    private lateinit var routines: RoutineRepository

    @Before
    fun setUp() {
        db = BunTapDatabase.inMemory(context)
        dir = File(context.cacheDir, "storage-test-${System.nanoTime()}").apply { mkdirs() }
        images = TargetImageStore(File(dir, "files"), File(dir, "cache"))
        targets = TargetRepository(db.targetDao(), images)
        routines = RoutineRepository(db.routineDao())
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun sampleBitmap(): Bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(Color.rgb(255, 194, 122))
        c.drawCircle(16f, 16f, 10f, Paint().apply { color = Color.BLUE })
    }

    @Test
    fun targetLifecycleKeepsFilesInSync() = runBlocking<Unit> {
        val t = targets.create("Play", sampleBitmap(), ScreenGeometry(720, 1280), MatchSettings(threshold = 0.9f), TargetActionSettings())
        assertTrue(images.exists(t.imageFile))
        val loaded = targets.get(t.id)!!
        assertEquals("Play", loaded.name)
        assertEquals(0.9f, loaded.match.threshold)
        assertEquals(64, loaded.width)

        targets.rename(t.id, "Play button")
        assertEquals("Play button", targets.get(t.id)!!.name)

        val copy = targets.duplicate(t.id)!!
        assertNotEquals(t.imageFile, copy.imageFile)
        assertTrue(images.exists(copy.imageFile))

        targets.delete(t.id)
        assertNull(targets.get(t.id))
        assertFalse(images.exists(t.imageFile))
        assertTrue(images.exists(copy.imageFile))
        assertEquals(1, targets.targets.first().size)
    }

    @Test
    fun routinesPersistAndDuplicateWithNewIds() = runBlocking<Unit> {
        val body = RoutineBody(actions = listOf(WaitAction("w1", 500), TapTargetAction("t1", "target-x")))
        val r = routines.create("Daily", "desc", body)
        val loaded = routines.get(r.id)!!
        assertEquals(body, loaded.body)

        val dup = routines.duplicate(r.id)!!
        assertEquals("Daily (copy)", dup.name)
        assertNotEquals(loaded.body.actions[0].id, dup.body.actions[0].id)

        routines.recordRun(r.id, RunStatus.COMPLETED.name)
        assertEquals("COMPLETED", routines.get(r.id)!!.lastRunStatus)
        routines.delete(r.id)
        assertNull(routines.get(r.id))
    }

    @Test
    fun clickerConfigPersists() = runBlocking<Unit> {
        val repo = ClickerRepository(db.clickerDao())
        assertEquals(ClickerConfig(), repo.get())
        val cfg = ClickerConfig(points = listOf(TapPoint("p", "P", 10, 20, rateOverride = TapRate.fromTps(3.0))), tapsPerPoint = 7)
        repo.save(cfg)
        assertEquals(cfg, repo.get())
        assertEquals(cfg, repo.config.first())
    }

    @Test
    fun historyRecordsSessionsAndEvents() = runBlocking<Unit> {
        val history = HistoryRepository(db)
        history.record(
            SessionRecord(
                kind = SessionKind.CLICKER, name = "Auto clicker", startedAtWallMs = 1_000, endedAtWallMs = 5_000,
                status = RunStatus.COMPLETED, reason = StopReason.COMPLETED, message = "ok",
                stats = StatsSnapshot(requestedTps = 5.0, averageObservedTps = 4.9, completed = 20, dispatched = 20, scheduled = 20, activeElapsedMs = 4_000),
                events = listOf(ExecutionEvent(0, EventType.INFO, "Started"), ExecutionEvent(4_000, EventType.INFO, "Done")),
            ),
        )
        val sessions = history.sessions.first()
        assertEquals(1, sessions.size)
        assertEquals(20, sessions[0].completedTaps)
        assertEquals(4.9, sessions[0].observedTps!!, 1e-9)
        assertEquals(2, history.events(sessions[0].id).first().size)
        history.clear()
        assertTrue(history.sessions.first().isEmpty())
        assertTrue(history.events(sessions[0].id).first().isEmpty())
    }

    @Test
    fun exportImportRoundTripWithRealImages() = runBlocking<Unit> {
        val t = targets.create("Collect", sampleBitmap(), null, MatchSettings(), TargetActionSettings())
        val r = routines.create("Collector", "", RoutineBody(actions = listOf(TapTargetAction("a1", t.id))))
        val library = RoutineLibrary(routines, targets, "test")
        val json = library.exportJson(r.id)!!
        assertTrue(json.contains("\"format\": \"buntap.routine\""))

        val parsed = library.parseImport(json, RateLimits(), ScreenGeometry(720, 1280))
        assertTrue("import failed: $parsed", parsed is ImportResult.Ok)
        val plan = (parsed as ImportResult.Ok).plan
        val imported = library.commitImport(plan)
        assertTrue(imported.needsReview)
        val newTargetId = (imported.body.actions[0] as TapTargetAction).targetId
        assertNotEquals(t.id, newTargetId)
        val newTarget = targets.get(newTargetId)
        assertNotNull(newTarget)
        assertTrue(images.exists(newTarget!!.imageFile))
        assertEquals(2, targets.getAll().size)

        // A corrupted file is rejected without creating anything.
        val broken = library.parseImport(json.replace("\"type\": \"tap_target\"", "\"type\": \"format_disk\""), RateLimits(), null)
        assertTrue(broken is ImportResult.Error)
        assertEquals(2, targets.getAll().size)
    }
}
