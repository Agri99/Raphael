package com.agri.karen.inference

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemoteInferenceBackendTest {

    private fun findFixturesDir(): File {
        val candidates = listOf(
            File("../protocol-fixtures/sse"),
            File("protocol-fixtures/sse"),
            File("../../protocol-fixtures/sse"),
            File("app/../protocol-fixtures/sse")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate protocol-fixtures/sse directory in candidates: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun testParseDeltaFixture() {
        val fixturesDir = findFixturesDir()
        val deltaFile = File(fixturesDir, "delta.sse")
        assertTrue("delta.sse fixture must exist", deltaFile.exists())

        val buffer = Buffer().writeUtf8(deltaFile.readText(Charsets.UTF_8))
        val events = mutableListOf<StreamEvent>()
        var completionSuccess: Boolean? = null
        var completionError: String? = null
        var completionWasDone: Boolean? = null

        RemoteInferenceBackend.parseSseStream(
            source = buffer,
            onEvent = { events.add(it) },
            onComplete = { success, error, wasDone ->
                completionSuccess = success
                completionError = error
                completionWasDone = wasDone
            }
        )

        assertEquals(1, events.size)
        val delta = events[0] as StreamEvent.Delta
        assertEquals("Hello from K.A.R.E.N.", delta.text)

        // Stream without done event completes with error
        assertEquals(false, completionSuccess)
        assertEquals(false, completionWasDone)
        assertTrue(completionError?.contains("before terminal done") == true)
    }

    @Test
    fun testParseDoneFixture() {
        val fixturesDir = findFixturesDir()
        val doneFile = File(fixturesDir, "done.sse")
        assertTrue("done.sse fixture must exist", doneFile.exists())

        val buffer = Buffer().writeUtf8(doneFile.readText(Charsets.UTF_8))
        val events = mutableListOf<StreamEvent>()
        var completionSuccess: Boolean? = null
        var completionError: String? = null
        var completionWasDone: Boolean? = null

        RemoteInferenceBackend.parseSseStream(
            source = buffer,
            onEvent = { events.add(it) },
            onComplete = { success, error, wasDone ->
                completionSuccess = success
                completionError = error
                completionWasDone = wasDone
            }
        )

        assertEquals(1, events.size)
        val done = events[0] as StreamEvent.Done
        assertEquals("stop", done.finishReason)
        assertEquals(true, completionSuccess)
        assertNull(completionError)
        assertEquals(true, completionWasDone)
    }

    @Test
    fun testParseErrorFixture() {
        val fixturesDir = findFixturesDir()
        val errorFile = File(fixturesDir, "error.sse")
        assertTrue("error.sse fixture must exist", errorFile.exists())

        val buffer = Buffer().writeUtf8(errorFile.readText(Charsets.UTF_8))
        val events = mutableListOf<StreamEvent>()
        var completionSuccess: Boolean? = null
        var completionError: String? = null
        var completionWasDone: Boolean? = null

        RemoteInferenceBackend.parseSseStream(
            source = buffer,
            onEvent = { events.add(it) },
            onComplete = { success, error, wasDone ->
                completionSuccess = success
                completionError = error
                completionWasDone = wasDone
            }
        )

        assertEquals(1, events.size)
        val err = events[0] as StreamEvent.Error
        assertEquals("MODEL_UNAVAILABLE", err.errorCode)
        assertEquals("Ollama backend is unreachable.", err.message)

        assertEquals(false, completionSuccess)
        assertEquals(false, completionWasDone)
        assertEquals("MODEL_UNAVAILABLE: Ollama backend is unreachable.", completionError)
    }

    @Test
    fun testParseFullSequenceDeltasThenDone() {
        val sseText = buildString {
            append("event: meta\ndata: {\"request_id\": \"req-123\", \"api_version\": \"v1\"}\n\n")
            append("event: delta\ndata: {\"text\": \"Part 1 \"}\n\n")
            append("event: delta\ndata: {\"text\": \"Part 2\"}\n\n")
            append("event: done\ndata: {\"finish_reason\": \"stop\"}\n\n")
        }

        val buffer = Buffer().writeUtf8(sseText)
        val events = mutableListOf<StreamEvent>()
        var completionSuccess: Boolean? = null

        RemoteInferenceBackend.parseSseStream(
            source = buffer,
            onEvent = { events.add(it) },
            onComplete = { success, _, _ -> completionSuccess = success }
        )

        assertEquals(4, events.size)
        assertTrue(events[0] is StreamEvent.Meta)
        assertEquals("Part 1 ", (events[1] as StreamEvent.Delta).text)
        assertEquals("Part 2", (events[2] as StreamEvent.Delta).text)
        assertTrue(events[3] is StreamEvent.Done)
        assertEquals(true, completionSuccess)
    }
}

