package com.clonsapps

import com.clonsapps.data.BuildState
import com.clonsapps.data.CloneJob
import com.clonsapps.data.InstallState
import com.clonsapps.data.JobCodec
import com.clonsapps.data.JobRules
import com.clonsapps.data.OutputFile
import com.clonsapps.data.RunState
import com.clonsapps.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobRulesTest {
    private fun job(id: String, build: BuildState, created: Long = 1, pkg: String = "a.b.clone2") = CloneJob(
        id = id, createdAt = created, sourceKind = SourceKind.INSTALLED, sourcePackage = "a.b", sourceLabel = "AB",
        sourceVersionName = "1.0", sourceVersionCode = 3, inputPaths = emptyList(), newPackage = pkg, newLabel = "AB 2",
        iconPath = null, outputName = "AB 2", build = build,
    )

    @Test
    fun jsonRoundTripKeepsEverything() {
        val j = job("x", BuildState.BUILT).copy(
            outputs = listOf(OutputFile("/p/base.apk", null, "aa", 10), OutputFile("/p/split.apk", "config.arm64_v8a", "bb", 5)),
            certSha256 = "cc", schemes = "v2 v3", changes = listOf("c1"), warnings = listOf("w1"), log = listOf("l1"),
            install = InstallState.INSTALLED, run = RunState.VERIFIED_SYSTEM, launchedAt = 99, progress = 1f,
        )
        val back = JobCodec.decode(JobCodec.encode(listOf(j)))
        assertEquals(listOf(j), back)
    }

    @Test
    fun corruptOrUnknownEntriesAreSkippedNotFatal() {
        val text = """{"version":1,"jobs":[{"id":"ok","sourcePackage":"a.b","newPackage":"a.b.c","build":"WHATEVER"},{"nope":1}]}"""
        val list = JobCodec.decode(text)
        assertEquals(1, list.size)
        assertEquals(BuildState.FAILED, list[0].build)
    }

    @Test
    fun recoveryAfterProcessDeathNeverReportsSuccess() {
        val running = job("r", BuildState.RUNNING).copy(progress = 0.7f, outputs = listOf(OutputFile("/x", null, "", 1)))
        val queued = job("q", BuildState.QUEUED)
        val built = job("b", BuildState.BUILT).copy(install = InstallState.INSTALLING)
        val r = JobRules.recover(listOf(running, queued, built))
        assertEquals(BuildState.INTERRUPTED, r[0].build)
        assertTrue(r[0].outputs.isEmpty())
        assertEquals("INTERRUPTED", r[0].errorCode)
        assertEquals(BuildState.QUEUED, r[1].build)
        assertEquals(InstallState.NOT_INSTALLED, r[2].install)
        assertTrue(r[0].canRetry)
    }

    @Test
    fun retryResetsState() {
        val failed = job("f", BuildState.FAILED).copy(errorCode = "CRC_MISMATCH", errorMessage = "x", log = listOf("a"))
        val r = JobRules.resetForRetry(failed)
        assertEquals(BuildState.QUEUED, r.build)
        assertNull(r.errorCode)
        assertTrue(r.log.isEmpty())
    }

    @Test
    fun queueOrderAndDuplicatePackages() {
        val a = job("a", BuildState.QUEUED, created = 5)
        val b = job("b", BuildState.QUEUED, created = 2, pkg = "a.b.clone3")
        val c = job("c", BuildState.BUILT, created = 1)
        assertEquals("b", JobRules.nextQueued(listOf(a, b, c))!!.id)
        assertTrue(JobRules.packageTakenInQueue(listOf(a, b, c), "a.b.clone2"))
        assertFalse(JobRules.packageTakenInQueue(listOf(a, b, c), "a.b.clone2", exceptId = "a"))
        assertFalse("job selesai tidak mengunci package", JobRules.packageTakenInQueue(listOf(c), "a.b.clone2"))
        assertNull(JobRules.nextQueued(listOf(c)))
    }
}
