package app.orchard.tv.ui.home

import org.junit.Assert.*
import org.junit.Test

class LaunchSessionMachineTest {
    private fun source(key: String = "player", at: Long = 100L) = LaunchSource(
        key = key, component = "$key/.Main", kind = LaunchEntryKind.App, boundsInWindow = null,
        folderId = null, homeIndex = 7, scrollDp = 110f, touchScrolled = false,
        windowWidth = 1920, windowHeight = 1080, requestedAt = at,
    )

    @Test fun repeatedOkSendsOnePendingRequest() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        for (at in listOf(150L, 250L, 350L, 450L)) assertNull(machine.begin(source(at = at)))
        assertEquals(first, machine.session)
    }

    @Test fun aDifferentSourceSupersedesPendingRequest() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        val second = machine.begin(source("settings", 200L))!!
        machine.restore(first.id)
        machine.finish(first.id)
        assertEquals(second, machine.session)
    }

    @Test fun returnRequiresAnActualPause() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        assertFalse(machine.resumed())
        assertEquals(LaunchPhase.Requested, machine.session!!.phase)
        machine.paused()
        assertEquals(LaunchPhase.Background, machine.session!!.phase)
        assertTrue(machine.resumed())
        assertTrue(machine.session!!.leftHome)
        assertEquals(LaunchPhase.Returning, machine.session!!.phase)
        assertFalse(machine.resumed())
        machine.finish(first.id)
        assertNull(machine.session)
    }

    @Test fun failedOrTimedOutRequestReturnsWithoutInventingBackground() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        machine.restore(first.id)
        assertEquals(LaunchPhase.Returning, machine.session!!.phase)
        assertFalse(machine.session!!.leftHome)
        machine.finish(first.id)
        assertNull(machine.session)
    }

    @Test fun staleTimeoutCannotOverwriteBackground() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        machine.paused()
        machine.restore(first.id)
        assertEquals(LaunchPhase.Background, machine.session!!.phase)
    }

    @Test fun navigationSuppressesReturnWithoutLosingPendingDeduplication() {
        val machine = LaunchSessionMachine()
        machine.begin(source())
        machine.suppressMotion()
        assertNull(machine.begin(source(at = 200L)))
        machine.paused()
        machine.resumed()
        assertFalse(machine.session!!.returnMotion)
        assertFalse(machine.session!!.restoreFocus)
    }

    @Test fun changedWindowSkipsMotionButKeepsStableFocus() {
        val machine = LaunchSessionMachine()
        machine.begin(source())
        machine.suppressMotion(keepFocus = true)
        machine.paused()
        machine.resumed()
        assertFalse(machine.session!!.returnMotion)
        assertTrue(machine.session!!.restoreFocus)
    }

    @Test fun interruptionAndExpiredRequestReleaseTheLock() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        assertNotNull(machine.begin(source(at = 1_300L)))
        machine.cancel()
        machine.restore(first.id)
        assertNull(machine.session)
        assertNotNull(machine.begin(source(at = 1_400L)))
    }

    // ---- Aerial's own zoom ----

    private fun zoomed(machine: LaunchSessionMachine, key: String = "player", at: Long = 100L) =
        machine.begin(source(key, at), zoom = true)!!

    @Test fun plainSessionHasNoZoom() {
        val machine = LaunchSessionMachine()
        val first = machine.begin(source())!!
        assertEquals(LaunchPhase.Requested, first.phase)
        assertEquals(ZoomTrack.Off, machine.session.zoomTrack)
        assertFalse(machine.dispatched(first.id))
        assertEquals(ZoomTrack.Off, (null as LaunchSession?).zoomTrack)
    }

    @Test fun zoomGrowsBeforeTheIntentAndDispatchesOnce() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        assertEquals(LaunchPhase.Opening, first.phase)
        assertEquals(ZoomTrack.Grow, machine.session.zoomTrack)
        assertTrue(machine.dispatched(first.id))
        assertEquals(LaunchPhase.Requested, machine.session!!.phase)
        assertEquals(ZoomTrack.Grow, machine.session.zoomTrack)
        assertFalse(machine.dispatched(first.id))
    }

    @Test fun repeatedOkDuringZoomSendsNothing() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        assertNull(machine.begin(source(at = 180L), zoom = true))
        machine.dispatched(first.id)
        assertNull(machine.begin(source(at = 400L), zoom = true))
        assertEquals(first.id, machine.session!!.id)
    }

    @Test fun supersededOrCancelledZoomNeverDispatches() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        val second = zoomed(machine, "settings", 150L)
        assertFalse(machine.dispatched(first.id))
        assertTrue(machine.dispatched(second.id))
        val third = zoomed(machine, "music", 300L)
        machine.cancel()
        assertFalse(machine.dispatched(third.id))
        assertNull(machine.session)
    }

    @Test fun failedZoomLaunchShrinksBackWithoutLeavingHome() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.restore(first.id)
        assertEquals(LaunchPhase.Returning, machine.session!!.phase)
        assertFalse(machine.session!!.leftHome)
        assertEquals(ZoomTrack.Shrink, machine.session.zoomTrack)
        machine.finish(first.id)
        assertNull(machine.session)
    }

    @Test fun timeoutBeforeDispatchAlsoShrinksBack() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.restore(first.id)
        assertEquals(ZoomTrack.Shrink, machine.session.zoomTrack)
        assertFalse(machine.dispatched(first.id))
    }

    @Test fun coveredZoomShrinksOnReturn() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        assertEquals(ZoomTrack.Grow, machine.session.zoomTrack)
        machine.stopped()
        assertTrue(machine.session!!.covered)
        assertFalse(machine.coverTimeout(first.id))
        assertTrue(machine.resumed())
        assertEquals(ZoomTrack.Shrink, machine.session.zoomTrack)
        machine.finish(first.id)
        assertNull(machine.session)
    }

    @Test fun uncoveredZoomRetractsAndAStopBringsItBack() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        assertTrue(machine.coverTimeout(first.id))
        assertEquals(ZoomTrack.Retract, machine.session.zoomTrack)
        // A slow cold start that does cover Aerial in the end still gets its return.
        machine.stopped()
        assertFalse(machine.session!!.retracted)
        assertEquals(ZoomTrack.Grow, machine.session.zoomTrack)
        machine.resumed()
        assertEquals(ZoomTrack.Shrink, machine.session.zoomTrack)
    }

    @Test fun retractedZoomReturnsWithoutMotion() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        machine.coverTimeout(first.id)
        machine.resumed()
        assertEquals(LaunchPhase.Returning, machine.session!!.phase)
        assertEquals(ZoomTrack.Drop, machine.session.zoomTrack)
        assertTrue(machine.session!!.restoreFocus)
    }

    @Test fun staleCoverTimeoutIsIgnored() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        assertFalse(machine.coverTimeout(first.id + 1))
        val plain = LaunchSessionMachine()
        val p = plain.begin(source())!!
        plain.paused()
        assertFalse(plain.coverTimeout(p.id))
    }

    @Test fun navigationOrResizeTurnsShrinkIntoDrop() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        machine.stopped()
        machine.suppressMotion(keepFocus = true)
        machine.resumed()
        assertEquals(ZoomTrack.Drop, machine.session.zoomTrack)
        assertTrue(machine.session!!.restoreFocus)
    }

    @Test fun pauseDuringReturnEndsTheSession() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.dispatched(first.id)
        machine.paused()
        machine.resumed()
        machine.paused()
        assertNull(machine.session)
    }

    @Test fun pauseWhileOpeningIsLeftToTheCaller() {
        val machine = LaunchSessionMachine()
        val first = zoomed(machine)
        machine.paused()
        assertEquals(LaunchPhase.Opening, machine.session!!.phase)
        assertFalse(machine.session!!.leftHome)
        assertFalse(machine.resumed())
        assertTrue(machine.dispatched(first.id))
    }

    // ---- The stuck overlay seen on MiTV-AZFP0 (2026-10-30), replayed ----

    /**
     * Open, onPause without onStop, Home pressed in the app, then onNewIntent / onResume / onPause /
     * onResume within 20 ms (Home's bounce off the stock launcher). onNewIntent keeps the source
     * (phase Background) and does not touch the machine.
     */
    @Test fun replayHomeBounceAfterAnUncoveredOpenLeavesNoZoom() {
        val m = LaunchSessionMachine()
        val id = zoomed(m).id
        assertTrue(m.dispatched(id))
        m.paused()
        assertEquals(LaunchPhase.Background, m.session!!.phase)
        assertFalse(m.session!!.covered)
        assertEquals(ZoomTrack.Grow, m.session.zoomTrack)
        // onNewIntent: keepSource (Background), no call. onResume:
        assertTrue(m.session.zoomReturnAhead)
        assertTrue(m.resumed())
        assertEquals(ZoomTrack.Shrink, m.session.zoomTrack)
        // onPause of the bounce: the return ends, nothing is left to draw.
        m.paused()
        assertNull(m.session)
        assertEquals(ZoomTrack.Off, m.session.zoomTrack)
        assertFalse(m.session.zoomReturnAhead)
        // onResume again: nothing to restore, and the next click is not deduplicated away.
        assertFalse(m.resumed())
        assertNull(m.session)
        assertNotNull(m.begin(source(at = 300L), zoom = true))
    }

    @Test fun replayHomeBounceAfterTheCoverTimeoutLeavesNoZoom() {
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        m.dispatched(first.id)
        m.paused()
        assertTrue(m.coverTimeout(first.id))
        assertEquals(ZoomTrack.Retract, m.session.zoomTrack)
        assertFalse(m.session.zoomReturnAhead)
        assertTrue(m.resumed())
        assertEquals(ZoomTrack.Drop, m.session.zoomTrack)
        m.paused()
        assertNull(m.session)
        assertFalse(m.resumed())
        assertEquals(ZoomTrack.Off, m.session.zoomTrack)
    }

    @Test fun replayStopArrivingWhileHomeBouncesLeavesNoZoom() {
        // The app's idle stop of Aerial could land between the bounce's pause and resume.
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        m.dispatched(first.id)
        m.paused()
        m.resumed()
        m.paused()
        m.stopped()
        assertNull(m.session)
        m.resumed()
        assertNull(m.session)
    }

    @Test fun replayStopAfterTheCoverTimeoutStillReturnsWithMotion() {
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        m.dispatched(first.id)
        m.paused()
        m.coverTimeout(first.id)
        m.stopped()
        assertTrue(m.session.zoomReturnAhead)
        m.resumed()
        assertEquals(ZoomTrack.Shrink, m.session.zoomTrack)
        m.finish(first.id)
        assertNull(m.session)
        assertFalse(m.session.zoomReturnAhead)
    }

    // ---- Holding the work of coming back while the zoom shrinks ----

    @Test fun returnQuietIsHeldOnlyForAReturnWithMotion() {
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        assertFalse(m.session.zoomReturnAhead) // Opening
        m.dispatched(first.id)
        assertFalse(m.session.zoomReturnAhead) // Requested
        m.paused()
        m.stopped()
        assertTrue(m.session.zoomReturnAhead) // onStart of the return
        m.resumed()
        assertTrue(m.session.zoomReturnAhead) // shrinking
        m.suppressMotion()
        assertFalse(m.session.zoomReturnAhead) // a key: Drop, nothing to wait for
        val plain = LaunchSessionMachine()
        plain.begin(source())
        plain.paused()
        assertFalse(plain.session.zoomReturnAhead)
        assertFalse((null as LaunchSession?).zoomReturnAhead)
    }

    @Test fun failedLaunchShrinksWithoutHoldingAnything() {
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        m.dispatched(first.id)
        m.restore(first.id)
        // Returning with motion, but Aerial never left: no start or resume comes to hold.
        assertEquals(ZoomTrack.Shrink, m.session.zoomTrack)
        assertFalse(m.session!!.leftHome)
    }

    @Test fun homeSeesOneStateWhileTheZoomLeaves() {
        val m = LaunchSessionMachine()
        val first = zoomed(m)
        val opening = m.session!!.forHome()
        m.dispatched(first.id)
        assertEquals(opening, m.session!!.forHome())
        m.paused()
        assertEquals(opening, m.session!!.forHome())
        m.stopped()
        assertEquals(opening, m.session!!.forHome())
        m.resumed()
        assertEquals(LaunchPhase.Returning, m.session!!.forHome().phase)
        assertTrue(m.session!!.forHome().leftHome)
        val plain = LaunchSessionMachine()
        val p = plain.begin(source())!!
        assertEquals(p, p.forHome())
        plain.paused()
        assertEquals(LaunchPhase.Background, plain.session!!.forHome().phase)
    }

    @Test fun settingsSessionCarriesItsShorterCoverTimeout() {
        val m = LaunchSessionMachine()
        val s = m.begin(source("settings"), zoom = true, coverTimeoutMs = LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS)!!
        assertEquals(LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS, s.coverTimeoutMs)
        m.dispatched(s.id)
        m.paused()
        assertEquals(LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS, m.session!!.coverTimeoutMs)
        assertTrue(LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS < LaunchSessionMachine.ZOOM_COVER_TIMEOUT_MS)
        assertEquals(LaunchSessionMachine.ZOOM_COVER_TIMEOUT_MS, zoomed(LaunchSessionMachine()).coverTimeoutMs)
    }

    @Test fun returnSettlesOnceFocusedAndFramesAreShortAgain() {
        assertFalse(returnSettled(1, Long.MAX_VALUE, focused = true))
        assertFalse(returnSettled(RETURN_SETTLE_MIN_FRAMES, 80L, focused = true))
        assertFalse(returnSettled(RETURN_SETTLE_MIN_FRAMES, 16L, focused = false))
        assertTrue(returnSettled(RETURN_SETTLE_MIN_FRAMES, 16L, focused = true))
        assertTrue(returnSettled(RETURN_SETTLE_MIN_FRAMES + 4, RETURN_SETTLED_FRAME_MS, focused = true))
        // The settle wait and the shrink fit in the quiet time, and the leftover check comes later.
        assertTrue(RETURN_SETTLE_MAX_MS + 450 < RETURN_QUIET_MAX_MS)
        assertTrue(RETURN_QUIET_MAX_MS < 1_500)
    }

    @Test fun heldWorkRunsOnceInTheOrderFirstHeld() {
        val ran = mutableListOf<String>()
        val held = HeldWork()
        held.hold("start") { ran += "start1" }
        held.hold("resume") { ran += "resume" }
        held.hold("start") { ran += "start2" } // replaces, keeps its place
        assertEquals(listOf("start", "resume"), held.keys)
        held.release()
        assertEquals(listOf("start2", "resume"), ran)
        assertTrue(held.isEmpty)
        held.release()
        assertEquals(2, ran.size)
    }

    @Test fun heldWorkDroppedNeverRuns() {
        var ran = false
        val held = HeldWork()
        held.hold("start") { ran = true }
        held.drop()
        held.release()
        assertFalse(ran)
    }

    @Test fun workHeldWhileReleasingWaitsForTheNextRelease() {
        val ran = mutableListOf<String>()
        val held = HeldWork()
        held.hold("start") {
            ran += "start"
            held.hold("again") { ran += "again" }
        }
        held.release()
        assertEquals(listOf("start"), ran)
        assertFalse(held.isEmpty)
        held.release()
        assertEquals(listOf("start", "again"), ran)
    }

    // ---- The picture of Home a zoom keeps for its return ----

    @Test fun pictureIsKeptFromTheClickUntilTheReturnHasHandedOver() {
        val m = LaunchSessionMachine()
        assertFalse(keepsHomeSnapshot(m.session))
        val s = zoomed(m)
        assertTrue(keepsHomeSnapshot(m.session)) // Opening
        m.dispatched(s.id)
        assertTrue(keepsHomeSnapshot(m.session)) // Requested
        m.paused()
        assertTrue(keepsHomeSnapshot(m.session)) // Background
        m.stopped()
        assertTrue(keepsHomeSnapshot(m.session)) // covered
        m.resumed()
        assertTrue(keepsHomeSnapshot(m.session)) // Returning from away
        m.finish(s.id)
        assertFalse(keepsHomeSnapshot(m.session)) // handed over to live Home
    }

    @Test fun pictureIsLetGoWhereverTheSessionEndsOtherwise() {
        // A plain launch never takes one.
        val plain = LaunchSessionMachine()
        plain.begin(source())
        assertFalse(keepsHomeSnapshot(plain.session))
        // Failed or timed out before Aerial left: Home stayed live.
        val failed = LaunchSessionMachine()
        val f = zoomed(failed)
        failed.dispatched(f.id)
        failed.restore(f.id)
        assertFalse(keepsHomeSnapshot(failed.session))
        val early = LaunchSessionMachine()
        val e = zoomed(early)
        early.restore(e.id)
        assertFalse(keepsHomeSnapshot(early.session))
        // Screen off, screensaver, Home-on-Home, another launch's cancel, dropIfStale.
        for (phase in 0..3) {
            val m = LaunchSessionMachine()
            val s = zoomed(m)
            if (phase >= 1) m.dispatched(s.id)
            if (phase >= 2) m.paused()
            if (phase >= 3) m.stopped()
            m.cancel()
            assertFalse("cancel at step $phase", keepsHomeSnapshot(m.session))
        }
        // A key or another window size during the return: no motion, no picture.
        val key = LaunchSessionMachine()
        val k = zoomed(key)
        key.dispatched(k.id)
        key.paused()
        key.stopped()
        key.resumed()
        key.suppressMotion()
        assertFalse(keepsHomeSnapshot(key.session))
    }

    @Test fun pictureSurvivesAZoomThatWentBackUnderAPanelUntilItsStopOrResume() {
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        m.dispatched(s.id)
        m.paused()
        assertTrue(m.coverTimeout(s.id))
        // Still Background: a stop may yet bring the full-screen frame back.
        assertTrue(keepsHomeSnapshot(m.session))
        m.stopped()
        assertTrue(keepsHomeSnapshot(m.session))
        // Resumed without the stop: it returns without motion (Drop) and the picture goes.
        val panel = LaunchSessionMachine()
        val p = zoomed(panel)
        panel.dispatched(p.id)
        panel.paused()
        panel.coverTimeout(p.id)
        panel.resumed()
        assertEquals(ZoomTrack.Drop, panel.session.zoomTrack)
        assertFalse(keepsHomeSnapshot(panel.session))
    }

    @Test fun pictureSizeIsTwoThirdsASideAndNeverEmpty() {
        assertEquals(1280 to 720, snapshotSize(1920, 1080))
        assertEquals(2560 to 1440, snapshotSize(3840, 2160))
        assertEquals(853 to 480, snapshotSize(1280, 720))
        assertEquals(1 to 1, snapshotSize(1, 1))
        val (w, h) = snapshotSize(1920, 1080)
        // 3.5 MB in graphics memory against 7.9 MB whole.
        assertTrue(w * h * 4 < 4 * 1024 * 1024)
    }

    @Test fun pictureIsLetGoAfterAwhileAndWhenMemoryIsShort() {
        assertFalse(snapshotStale(0))
        assertFalse(snapshotStale(SNAPSHOT_AWAY_MAX_MS - 1))
        assertTrue(snapshotStale(SNAPSHOT_AWAY_MAX_MS))
        assertTrue(snapshotStale(SNAPSHOT_AWAY_MAX_MS * 10))
        // ComponentCallbacks2: RUNNING_MODERATE 5, RUNNING_LOW 10, RUNNING_CRITICAL 15, UI_HIDDEN 20,
        // BACKGROUND 40, MODERATE 60, COMPLETE 80. UI_HIDDEN comes with every app that opens.
        assertFalse(trimLetsGo(5))
        assertTrue(trimLetsGo(10))
        assertTrue(trimLetsGo(15))
        assertFalse(trimLetsGo(20))
        assertTrue(trimLetsGo(40))
        assertTrue(trimLetsGo(60))
        assertTrue(trimLetsGo(80))
    }

    @Test fun overThePictureTheShrinkStartsAtTheFirstFocusedFrameWhateverItsLength() {
        // Focus and one frame of the window; not Home's frames, and not a short frame: the first
        // frames after the stop are the slow ones (103 and 116 ms), the spring's clamped step
        // carries them.
        assertFalse(snapshotSettled(0, Long.MAX_VALUE, focused = true))
        assertFalse(snapshotSettled(SNAPSHOT_SETTLE_MIN_FRAMES, 16L, focused = false))
        assertTrue(snapshotSettled(SNAPSHOT_SETTLE_MIN_FRAMES, 16L, focused = true))
        assertTrue(snapshotSettled(SNAPSHOT_SETTLE_MIN_FRAMES, 120L, focused = true))
        assertTrue(snapshotSettled(SNAPSHOT_SETTLE_MIN_FRAMES + 3, Long.MAX_VALUE, focused = true))
        // Over still Home the old rule stands: three frames, the last one short.
        assertFalse(returnSettled(SNAPSHOT_SETTLE_MIN_FRAMES, 30L, focused = true))
        assertTrue(SNAPSHOT_SETTLE_MIN_FRAMES < RETURN_SETTLE_MIN_FRAMES)
        assertTrue(SNAPSHOT_SETTLE_MAX_MS < RETURN_SETTLE_MAX_MS)
    }

    // ---- The picture is taken late, once the zoom has grown ----

    @Test fun pictureIsTakenOnlyWhileAerialIsWholeAndTheAppWasAskedFor() {
        assertFalse(wantsLateSnapshot(null))
        // The grow's end with the app asked for (240 ms in, grow 400 ms): yes.
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        // Still Opening at the grow's end: the dispatch was cancelled or never ran; nothing to keep.
        assertFalse(wantsLateSnapshot(m.session))
        m.dispatched(s.id)
        assertTrue(wantsLateSnapshot(m.session)) // Requested
        m.paused()
        assertTrue(wantsLateSnapshot(m.session)) // Background, the window still whole (pause comes before the stop)
        // Stopped behind the app: its hardware layers may be gone, the return takes the old path.
        m.stopped()
        assertFalse(wantsLateSnapshot(m.session))
        m.resumed()
        assertFalse(wantsLateSnapshot(m.session)) // Returning: Home is live, nothing to take
    }

    @Test fun noPictureIsTakenForAPlainLaunchAFailedOneOrAZoomThatWentBack() {
        val plain = LaunchSessionMachine()
        plain.begin(source())
        assertFalse(wantsLateSnapshot(plain.session))
        val failed = LaunchSessionMachine()
        val f = zoomed(failed)
        failed.dispatched(f.id)
        failed.restore(f.id)
        assertFalse(wantsLateSnapshot(failed.session))
        // Never covered in time: the zoom went back into its tile, Home stayed live.
        val panel = LaunchSessionMachine()
        val p = zoomed(panel)
        panel.dispatched(p.id)
        panel.paused()
        assertTrue(panel.coverTimeout(p.id))
        assertFalse(wantsLateSnapshot(panel.session))
        // A key or another window size: the return is without motion, nothing to keep.
        val key = LaunchSessionMachine()
        val k = zoomed(key)
        key.dispatched(k.id)
        key.suppressMotion()
        assertFalse(wantsLateSnapshot(key.session))
    }

    @Test fun whatTheLateRuleWantsIsAlwaysWhatTheSessionKeeps() {
        // The rule may only say yes where the picture would be kept: taken, it is never let go at once.
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        for (step in 0..4) {
            if (step == 1) m.dispatched(s.id)
            if (step == 2) m.paused()
            if (step == 3) m.stopped()
            if (step == 4) m.resumed()
            if (wantsLateSnapshot(m.session)) assertTrue("step $step", keepsHomeSnapshot(m.session))
        }
    }

    // ---- Home's reaction to Aerial's pause, held while the zoom grows ----

    @Test fun pauseIsHeldOnlyWhileAerialIsPausedAndTheZoomStillGrowsUncovered() {
        assertFalse(holdsPauseForGrow(null, false))
        // A plain launch has no zoom to spare.
        val plain = LaunchSessionMachine()
        plain.begin(source())
        plain.paused()
        assertFalse(holdsPauseForGrow(plain.session, false))
        // Not paused yet: nothing to hold.
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        assertFalse(holdsPauseForGrow(m.session, false)) // Opening
        m.dispatched(s.id)
        assertFalse(holdsPauseForGrow(m.session, false)) // Requested, still in front
        m.paused()
        assertTrue(holdsPauseForGrow(m.session, false)) // Background, growing, window whole
        // The grow has ended and the overlay hides Home: the pause is seen (under the overlay).
        assertFalse(holdsPauseForGrow(m.session, true))
        m.stopped()
        assertFalse(holdsPauseForGrow(m.session, false)) // covered
    }

    @Test fun everyWayOutOfTheGrowEndsTheHold() {
        fun paused(): Pair<LaunchSessionMachine, LaunchSession> {
            val m = LaunchSessionMachine()
            val s = zoomed(m)
            m.dispatched(s.id)
            m.paused()
            assertTrue(holdsPauseForGrow(m.session, false))
            return m to s
        }
        // The app covers Aerial.
        paused().let { (m, _) -> m.stopped(); assertFalse(holdsPauseForGrow(m.session, false)) }
        // Back to Aerial before it was covered (Home pressed, the app closed at once).
        paused().let { (m, _) -> assertTrue(m.resumed()); assertFalse(holdsPauseForGrow(m.session, false)) }
        // Nothing covered it in time (a panel): the zoom goes back into its tile.
        paused().let { (m, s) -> assertTrue(m.coverTimeout(s.id)); assertFalse(holdsPauseForGrow(m.session, false)) }
        // Cancelled (the screen off, the screensaver, Home on Home).
        paused().let { (m, _) -> m.cancel(); assertFalse(holdsPauseForGrow(m.session, false)) }
        // Another launch.
        paused().let { (m, _) -> assertNotNull(m.begin(source("settings", 5_000L), zoom = true)); assertFalse(holdsPauseForGrow(m.session, false)) }
        // A key does not end it, the grow does: the zoom keeps growing under a suppressed return.
        paused().let { (m, _) -> m.suppressMotion(); assertTrue(holdsPauseForGrow(m.session, false)) }
        // Neither a stale timeout nor a finish for another session touches it.
        paused().let { (m, s) -> m.restore(s.id); m.finish(s.id); assertTrue(holdsPauseForGrow(m.session, false)) }
    }

    @Test fun aHoldThatEndedCannotComeBackForTheSameSession() {
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        m.dispatched(s.id)
        m.paused()
        assertTrue(holdsPauseForGrow(m.session, false))
        // Retracted, then covered after all: back on the grow track but covered.
        assertTrue(m.coverTimeout(s.id))
        assertFalse(holdsPauseForGrow(m.session, false))
        m.stopped()
        assertEquals(ZoomTrack.Grow, m.session.zoomTrack)
        assertFalse(holdsPauseForGrow(m.session, false))
        m.resumed()
        assertFalse(holdsPauseForGrow(m.session, false))
        m.finish(s.id)
        assertFalse(holdsPauseForGrow(m.session, false))
        assertNull(m.session)
    }

    @Test fun theHoldsTimeoutIsLongerThanAGrowAndShorterThanWhatEndsTheZoomAnyway() {
        // The grow is 400 ms from the click and the pause comes after 325-413 ms of it.
        assertTrue(PAUSE_HOLD_MAX_MS >= 800)
        assertTrue(PAUSE_HOLD_MAX_MS <= LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS)
        assertTrue(PAUSE_HOLD_MAX_MS < ZOOM_LEFTOVER_MS)
    }

    // ---- The film held on its frame while the zoom grows ----

    @Test fun theFilmIsHeldFromTheClickThroughTheWholeGrow() {
        assertFalse(holdsFilmForGrow(null))
        // A plain launch has no zoom: the film is not touched.
        val plain = LaunchSessionMachine()
        plain.begin(source())
        assertFalse(holdsFilmForGrow(plain.session))
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        assertTrue(holdsFilmForGrow(m.session)) // Opening: from the click
        m.dispatched(s.id)
        assertTrue(holdsFilmForGrow(m.session)) // Requested
        m.paused()
        assertTrue(holdsFilmForGrow(m.session)) // Background, window whole
        m.stopped()
        assertTrue(holdsFilmForGrow(m.session)) // covered: the film's own code lets it go
    }

    @Test fun everyWayOutOfTheGrowEndsTheFilmHold() {
        fun opened(): Pair<LaunchSessionMachine, LaunchSession> {
            val m = LaunchSessionMachine()
            val s = zoomed(m)
            m.dispatched(s.id)
            assertTrue(holdsFilmForGrow(m.session))
            return m to s
        }
        // The request failed or timed out with Aerial in front: the zoom turns back, the film plays on.
        opened().let { (m, s) -> m.restore(s.id); assertFalse(holdsFilmForGrow(m.session)) }
        // Home pressed in the first 240 ms, before the app was asked for.
        LaunchSessionMachine().let { m -> zoomed(m); m.cancel(); assertFalse(holdsFilmForGrow(m.session)) }
        // Cancelled later (the screen off, the screensaver, Home on Home).
        opened().let { (m, _) -> m.cancel(); assertFalse(holdsFilmForGrow(m.session)) }
        // Back to Aerial before it was covered.
        opened().let { (m, _) -> m.paused(); assertTrue(m.resumed()); assertFalse(holdsFilmForGrow(m.session)) }
        // Nothing covered it in time (a panel): the zoom goes back into its tile.
        opened().let { (m, s) -> m.paused(); assertTrue(m.coverTimeout(s.id)); assertFalse(holdsFilmForGrow(m.session)) }
        // Another launch is its own grow (held again), never the old one's.
        opened().let { (m, _) -> assertNotNull(m.begin(source("settings", 5_000L), zoom = true)); assertTrue(holdsFilmForGrow(m.session)) }
        opened().let { (m, _) -> assertNotNull(m.begin(source("settings", 5_000L))); assertFalse(holdsFilmForGrow(m.session)) }
        // A key does not end it: the zoom keeps growing under a suppressed return.
        opened().let { (m, _) -> m.suppressMotion(); assertTrue(holdsFilmForGrow(m.session)) }
        // The return (after the stop) and the end of the session.
        opened().let { (m, s) -> m.paused(); m.stopped(); m.resumed(); assertFalse(holdsFilmForGrow(m.session)); m.finish(s.id); assertFalse(holdsFilmForGrow(m.session)) }
    }

    @Test fun theFilmHoldIsExactlyTheGrowTrack() {
        // Whatever the machine does, the film is held if and only if the zoom is growing: the two
        // cannot drift apart, so a hold cannot outlive its grow on a path nobody thought of.
        val orders = listOf(
            listOf(0, 1, 3, 5, 7), listOf(7, 6, 5, 4, 3, 2, 1, 0), listOf(1, 0, 4, 2, 6), listOf(2, 0, 1, 3, 4, 5, 6, 7),
            listOf(0, 4, 1, 4, 3, 0, 5), listOf(6, 0, 1, 7, 2, 3),
        )
        for (order in orders) {
            val machine = LaunchSessionMachine()
            val z = zoomed(machine)
            val moves = listOf<() -> Unit>(
                { machine.dispatched(z.id) }, { machine.paused() }, { machine.suppressMotion() }, { machine.stopped() },
                { machine.coverTimeout(z.id) }, { machine.resumed() }, { machine.restore(z.id) }, { machine.finish(z.id) },
            )
            for (i in order) {
                moves[i]()
                assertEquals("$order after step $i", machine.session.zoomTrack == ZoomTrack.Grow, holdsFilmForGrow(machine.session))
            }
        }
    }

    @Test fun aHeldFilmPlaysOnOnlyWhereAerialIsInFrontAndHomeWantsItMoving() {
        assertTrue(thawsFilm(resumed = true, wanted = true))
        // Aerial left: the film's own code has let the player go, a new one starts on the way back.
        assertFalse(thawsFilm(resumed = false, wanted = true))
        // A key or a panel stopped it meanwhile, or the return's quiet time is on.
        assertFalse(thawsFilm(resumed = true, wanted = false))
        assertFalse(thawsFilm(resumed = false, wanted = false))
    }

    // ---- Home recorded for the picture but not drawn ----

    @Test fun homeIsUnseenOnlyUnderAZoomThatHoldsGrownOverTheWholeWindow() {
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        m.dispatched(s.id)
        m.paused()
        assertTrue(homeUnseenUnderGrownZoom(m.session, covers = true))
        // Not covering yet (still growing, or a sliver of the window left): Home is drawn.
        assertFalse(homeUnseenUnderGrownZoom(m.session, covers = false))
        assertFalse(homeUnseenUnderGrownZoom(null, covers = true))
        // The zoom went back into its tile, a failed launch, the return: Home is drawn at once.
        val retracted = LaunchSessionMachine()
        val r = zoomed(retracted)
        retracted.dispatched(r.id)
        retracted.paused()
        assertTrue(retracted.coverTimeout(r.id))
        assertFalse(homeUnseenUnderGrownZoom(retracted.session, covers = true))
        val failed = LaunchSessionMachine()
        val f = zoomed(failed)
        failed.dispatched(f.id)
        failed.restore(f.id)
        assertFalse(homeUnseenUnderGrownZoom(failed.session, covers = true))
        m.stopped()
        m.resumed()
        assertFalse(homeUnseenUnderGrownZoom(m.session, covers = true))
        // A plain launch has no zoom.
        val plain = LaunchSessionMachine()
        plain.begin(source())
        assertFalse(homeUnseenUnderGrownZoom(plain.session, covers = true))
    }

    @Test fun theWindowDrawsHomeUnlessItIsHiddenOrUnseen() {
        assertTrue(drawsHomeLayer(hidden = false, unseen = false))
        assertFalse(drawsHomeLayer(hidden = false, unseen = true))
        assertFalse(drawsHomeLayer(hidden = true, unseen = false))
        assertFalse(drawsHomeLayer(hidden = true, unseen = true))
    }

    // ---- The shrink's spring ----

    private fun spring(x0: Float = 1f, v0: Float = 0f) = CriticalSpring(x0, v0)

    @Test fun springFollowsTheClosedFormAndNeverOvershoots() {
        val w = Math.sqrt(SHRINK_STIFFNESS.toDouble())
        val s = spring()
        var t = 0.0
        var last = 1f
        repeat(60) {
            s.step(1f / 60f, SHRINK_STIFFNESS)
            t += 1.0 / 60.0
            val closed = ((1.0 + w * t) * Math.exp(-w * t)).toFloat()
            assertEquals(closed, s.value, 0.0005f)
            assertTrue("never below the target", s.value >= 0f)
            assertTrue("monotone", s.value <= last + 1e-6f)
            last = s.value
        }
    }

    @Test fun springGivesTheSamePlaceWhateverTheSteps() {
        // The driver may take any step (clamped or not) without changing where the spring is at a time.
        val one = spring().also { it.step(0.2f, SHRINK_STIFFNESS) }
        val two = spring().also { it.step(0.12f, SHRINK_STIFFNESS); it.step(0.08f, SHRINK_STIFFNESS) }
        val many = spring().also { repeat(20) { _ -> it.step(0.01f, SHRINK_STIFFNESS) } }
        assertEquals(one.value, two.value, 0.0001f)
        assertEquals(one.value, many.value, 0.0001f)
        assertEquals(one.velocity, two.velocity, 0.001f)
        // A step of nothing, or a backwards one, changes nothing.
        val before = one.value
        one.step(0f, SHRINK_STIFFNESS)
        one.step(-1f, SHRINK_STIFFNESS)
        assertEquals(before, one.value, 0f)
    }

    @Test fun springSettlesOnTheTargetInAboutFourHundredMilliseconds() {
        val s = spring()
        var ms = 0
        while (!s.settled(0.002f) && ms < 2000) { s.step(0.001f, SHRINK_STIFFNESS); ms++ }
        assertTrue("settled after $ms ms", ms in 380..480)
        // Taking over the velocity of a grow (a failed launch turning back): continues from it.
        val moving = spring(0.9f, 1.5f)
        moving.step(0.001f, SHRINK_STIFFNESS)
        assertTrue(moving.value > 0.9f)
        // A target other than 0.
        val up = CriticalSpring(0f, 0f, target = 1f)
        repeat(1000) { up.step(0.002f, SHRINK_STIFFNESS) }
        assertTrue(up.settled(0.002f))
        assertEquals(1f, up.value, 0.001f)
    }

    @Test fun aSlowFirstFrameCountsAsTwoVsyncsWhileTheSpringIsYoung() {
        assertEquals(0.032f, shrinkStepSeconds(116L, 0L), 0f) // the measured slow frame
        assertEquals(0.032f, shrinkStepSeconds(103L, 0L), 0f)
        assertEquals(0.016f, shrinkStepSeconds(16L, 20L), 0f)
        assertEquals(0f, shrinkStepSeconds(-5L, 0L), 0f)
        assertEquals(0f, shrinkStepSeconds(0L, 0L), 0f)
        // Older than the window: the real interval, bounded.
        assertEquals(0.116f, shrinkStepSeconds(116L, SHRINK_CLAMP_WINDOW_MS), 0f)
        assertEquals(0.2f, shrinkStepSeconds(5_000L, SHRINK_CLAMP_WINDOW_MS + 1), 0f)
        assertEquals(0.032f, shrinkStepSeconds(116L, SHRINK_CLAMP_WINDOW_MS - 1), 0f)
    }

    @Test fun aSlowFrameCannotMakeTheShrinkJump() {
        // Unclamped, the 116 ms frame takes the spring 63% of the way at once; clamped, 15%.
        val raw = spring().also { it.step(0.116f, SHRINK_STIFFNESS) }
        val clamped = spring().also { it.step(shrinkStepSeconds(116L, 0L), SHRINK_STIFFNESS) }
        assertTrue("raw ${raw.value}", 1f - raw.value > 0.6f)
        assertTrue("clamped ${clamped.value}", 1f - clamped.value < 0.2f)
    }

    @Test fun shrinkUnderSlowFramesStillLandsWithinTheQuietTime() {
        // The worst case the rule allows: every frame 100 ms from the start of the spring (nothing
        // else can run in a frame that long). Young frames count 32 ms, later ones their own 100.
        val s = spring()
        var wall = 0L
        var frames = 0
        while (!s.settled(0.002f) && wall < 10_000) {
            wall += 100
            s.step(shrinkStepSeconds(100L, wall - 100), SHRINK_STIFFNESS)
            frames++
        }
        // After the quiet time's own shrink share: wait + spring + hand-over + fade stays below the
        // leftover check, and with frames of 100 ms the spring ends within a second and a half.
        assertTrue("took $wall ms in $frames frames", wall <= 1500)
        assertTrue(SNAPSHOT_SETTLE_MAX_MS + wall < ZOOM_LEFTOVER_MS)
        // Ordinary frames: the spring is unchanged by the clamp (16.7 ms is below it).
        val smooth = spring()
        var ordinary = 0L
        while (!smooth.settled(0.002f)) { smooth.step(shrinkStepSeconds(17L, ordinary), SHRINK_STIFFNESS); ordinary += 17 }
        assertTrue("ordinary $ordinary ms", ordinary in 380..480)
    }

    @Test fun theWholeReturnFitsTheQuietTimeAndTheLeftoverCheckComesAfter() {
        val shrink = 450L // the spring, to within 3 px
        val over = SNAPSHOT_SETTLE_MAX_MS + shrink + HANDOVER_MAX_MS + SNAPSHOT_FADE_MS
        assertTrue("$over", over < RETURN_QUIET_MAX_MS)
        assertTrue(RETURN_QUIET_MAX_MS < ZOOM_LEFTOVER_MS)
        // Without a picture the old path: still frames, then the shrink.
        assertTrue(RETURN_SETTLE_MAX_MS + shrink < RETURN_QUIET_MAX_MS)
    }

    // ---- The overlay's last frame is the dimmed artwork, never a flat colour ----

    @Test fun theFullScreenFrameKeepsTheArtworkDimmedAndNeverFlat() {
        assertEquals(1f, fullScreenLight(0f), 0.0001f)
        // At full screen the brightest artwork (white, 255) is left under half its light: a mid grey
        // at most, never the flat near-white frame that stuck as the system's snapshot of Aerial.
        val atFull = fullScreenLight(1f)
        assertEquals(1f - FULL_SCREEN_DIM, atFull, 0.0001f)
        assertTrue(atFull * 255 < 128)
        // Monotonic on the way, clamped outside 0..1.
        var last = 2f
        for (i in 0..20) {
            val l = fullScreenLight(i * 0.05f)
            assertTrue(l <= last + 0.0001f)
            last = l
        }
        assertEquals(atFull, fullScreenLight(3f), 0.0001f)
        assertEquals(1f, fullScreenLight(-1f), 0.0001f)
    }

    @Test fun aKeyEndsTheHoldOnlyWhileTheGrownZoomWaitsForTheApp() {
        assertFalse(keyEndsHold(null, grown = true))
        val plain = LaunchSessionMachine()
        plain.begin(source())
        assertFalse(keyEndsHold(plain.session, grown = true))
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        // Opening (the app not asked for yet) and a zoom still growing: keys stay swallowed by Home.
        assertFalse(keyEndsHold(m.session, grown = true))
        m.dispatched(s.id)
        assertFalse(keyEndsHold(m.session, grown = false))
        assertTrue(keyEndsHold(m.session, grown = true)) // asked for, grown, Aerial still in front
        // Paused: Aerial gets no keys any more; and after the stop or on the return, nothing to end.
        m.paused()
        assertFalse(keyEndsHold(m.session, grown = true))
        m.stopped()
        assertFalse(keyEndsHold(m.session, grown = true))
        m.resumed()
        assertFalse(keyEndsHold(m.session, grown = true))
    }

    @Test fun endingTheHoldBeforeAerialPausesGoesBackIntoTheTileAndLeavesNoHoldBehind() {
        val m = LaunchSessionMachine()
        val s = zoomed(m)
        m.dispatched(s.id)
        assertTrue(keyEndsHold(m.session, grown = true))
        m.restore(s.id) // what endHoldOnKey does
        assertEquals(ZoomTrack.Shrink, m.session.zoomTrack)
        assertFalse(keyEndsHold(m.session, grown = true)) // once, never twice
        assertFalse(holdsFilmForGrow(m.session))
        assertFalse(m.session!!.leftHome)
        // The app that starts anyway pauses Aerial in the Returning phase: the session ends, the overlay goes.
        m.paused()
        assertNull(m.session)
    }

    // ---- Zoom geometry ----

    private val window =androidx.compose.ui.geometry.Rect(0f, 0f, 1920f, 1080f)
    private val tile = androidx.compose.ui.geometry.Rect(200f, 300f, 540f, 491.25f)

    @Test fun zoomStartsOnTheTileAndEndsCoveringTheWindow() {
        val start = zoomFrame(tile, window, 0f, 28f)
        assertEquals(tile, start.rect)
        assertEquals(28f, start.radius, 0.001f)
        val end = zoomFrame(tile, window, 1f, 28f)
        assertEquals(0f, end.radius, 0.001f)
        assertTrue(end.rect.left <= 0.01f && end.rect.top <= 0.01f)
        assertTrue(end.rect.right >= 1919.99f && end.rect.bottom >= 1079.99f)
        val mid = zoomFrame(tile, window, 0.5f, 28f)
        assertEquals((tile.width + end.rect.width) / 2f, mid.rect.width, 0.01f)
        assertEquals(14f, mid.radius, 0.001f)
    }

    @Test fun zoomKeepsTheTilesAspectOnAnotherScreenShape() {
        val tall = androidx.compose.ui.geometry.Rect(0f, 0f, 1440f, 1080f)
        val end = zoomFrame(tile, tall, 1f, 28f)
        assertEquals(tile.width / tile.height, end.rect.width / end.rect.height, 0.001f)
        assertEquals(tall.center.x, end.rect.center.x, 0.01f)
        assertTrue(end.rect.left <= 0f && end.rect.right >= 1440f && end.rect.top <= 0.01f && end.rect.bottom >= 1079.99f)
    }

    @Test fun zoomProgressIsClampedAndEmptyTilesCoverAtOnce() {
        assertEquals(zoomFrame(tile, window, 1f, 28f), zoomFrame(tile, window, 1.2f, 28f))
        assertEquals(tile, zoomFrame(tile, window, -0.1f, 28f).rect)
        assertEquals(window, zoomFrame(androidx.compose.ui.geometry.Rect.Zero, window, 0.3f, 28f).rect)
    }
}
