package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalChipLayoutTests {

    private fun box(left: Float, top: Float, width: Float = 100f, height: Float = 26f) =
        JournalChipBox(left, top, left + width, top + height)

    private fun spread(boxes: List<JournalChipBox>, members: IntArray, maxX: Float = 1000f, minTop: Float = 8f, maxTop: Float = 400f) =
        JournalChipLayout.spread(
            boxes = boxes,
            members = members,
            minX = 0f,
            maxX = maxX,
            minTop = minTop,
            maxTop = maxTop,
            gapPx = 4f,
            ringStepPx = 4f,
            maxRadiusPx = 200f
        )

    private fun moved(boxes: List<JournalChipBox>, members: IntArray, shifts: Array<FloatArray>): List<JournalChipBox> {
        val result = boxes.toMutableList()
        members.forEachIndexed { slot, index ->
            val box = boxes[index]
            val (dx, dy) = shifts[slot].let { it[0] to it[1] }
            result[index] = JournalChipBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
        }
        return result
    }

    private fun overlapping(a: JournalChipBox, b: JournalChipBox) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun theFrontChipStaysAndTheOneBehindMovesTheShortestWay() {
        // Chip 1 is the pile's front, so it keeps its place.
        val boxes = listOf(box(0f, 200f), box(5f, 188f))
        val members = intArrayOf(1, 0)

        val shifts = spread(boxes, members)

        assertArrayEquals(floatArrayOf(0f, 0f), shifts[0], 0.001f)
        val result = moved(boxes, members, shifts)
        assertTrue(!overlapping(result[0], result[1]))
        // Straight down is the shortest way clear: 188 + 26 + 4 = 218 is 18px below 200.
        assertEquals(0f, shifts[1][0], 0.5f)
        assertEquals(20f, shifts[1][1], 0.5f)
    }

    @Test
    fun theFrontStaysEvenWhenAnEdgeOutsideThePileTouchesIt() {
        // Chip 2, outside the pile, shingles 2px onto the front's top edge.
        val boxes = listOf(box(0f, 200f), box(5f, 206f), box(0f, 176f))

        val shifts = spread(boxes, intArrayOf(0, 1))

        assertArrayEquals(floatArrayOf(0f, 0f), shifts[0], 0.001f)
    }

    @Test
    fun aChipMovesSidewaysWhenUpAndDownAreBlocked() {
        // Chip 0 hides under chip 1; chips 2 and 3 sit just above and below, outside the group,
        // and the chart ends right past them, so neither way up nor down has room.
        val boxes = listOf(box(0f, 200f), box(10f, 196f), box(0f, 160f), box(0f, 240f))

        val shifts = spread(boxes, intArrayOf(1, 0), minTop = 150f, maxTop = 236f)

        val result = moved(boxes, intArrayOf(1, 0), shifts)
        for (i in result.indices) for (j in result.indices) {
            if (i < j) assertTrue("chips $i and $j overlap", !overlapping(result[i], result[j]))
        }
        assertTrue(shifts[1][0] != 0f)
    }

    @Test
    fun spreadNeverLandsOnChipsOutsideTheGroup() {
        val boxes = listOf(box(0f, 200f), box(5f, 190f), box(10f, 180f), box(0f, 226f), box(120f, 200f))
        val members = intArrayOf(2, 1, 0)

        val result = moved(boxes, members, spread(boxes, members))

        for (i in result.indices) for (j in result.indices) {
            if (i < j && (i in members || j in members)) {
                assertTrue("chips $i and $j overlap", !overlapping(result[i], result[j]))
            }
        }
    }

    @Test
    fun spreadStaysOnTheChart() {
        val boxes = listOf(box(0f, 10f), box(2f, 8f))
        val members = intArrayOf(0, 1)

        val result = moved(boxes, members, spread(boxes, members, maxX = 300f, minTop = 8f, maxTop = 300f))

        result.forEach { box ->
            assertTrue(box.left >= 0f && box.right <= 300f && box.top >= 8f && box.top <= 300f)
        }
    }

    // --- Placement ---

    private val spec = JournalChipLayout.Spec(
        chipHeight = 26f,
        sideOffset = 10f,
        rowStep = 30f,
        nudgeStep = 12f,
        gap = 4f,
        minX = 0f,
        maxX = 400f,
        minTop = 8f,
        maxTop = 300f
    )

    private fun request(
        anchorX: Float,
        baseTop: Float = 200f,
        width: Float = 80f,
        previous: JournalChipSlot? = null,
        stackKey: Any? = null,
        foldInto: Int? = null
    ) = JournalChipRequest(anchorX, baseTop, width, previous, stackKey, foldInto)

    private val stacking = spec.copy(peek = 8f, repeatReach = 100f)

    private fun assertNoOverlaps(placements: List<JournalChipPlacement>) {
        for (i in placements.indices) for (j in placements.indices) {
            if (i < j) assertTrue("chips $i and $j overlap", !overlapping(placements[i].box, placements[j].box))
        }
    }

    @Test
    fun aChipWithRoomSitsWhereItAlwaysDid() {
        val placed = JournalChipLayout.place(listOf(request(50f)), spec).single()

        assertEquals(JournalChipSlot.Preferred, placed.slot)
        assertEquals(JournalChipBox(60f, 200f, 140f, 226f), placed.box)
    }

    @Test
    fun twoEntriesCloseTogetherSitBackToBackAroundThemInsteadOfStacking() {
        // The second entry's own right-hand spot is taken, but its left side is free.
        val placements = JournalChipLayout.place(listOf(request(100f), request(110f)), spec)

        assertEquals(JournalChipSlot.Preferred, placements[0].slot)
        assertEquals(JournalChipSlot(side = -1, lift = 0, nudge = 0), placements[1].slot)
        assertNoOverlaps(placements)
    }

    @Test
    fun aCrowdUsesRoomSidewaysAndAboveWithoutOverlapping() {
        // Six doses a minute apart, as a loop posts them, at 1h zoom on a 400px chart.
        val placements = JournalChipLayout.place((0 until 6).map { request(150f + it * 7f) }, spec)

        assertNoOverlaps(placements)
        assertTrue(placements.none { it.crowded })
        // Some of them use the room beside the entries rather than all climbing.
        assertTrue(placements.any { it.slot.lift == 0 && it.slot != JournalChipSlot.Preferred })
    }

    @Test
    fun aChipNearTheRightEdgeHangsToTheLeftOfItsEntry() {
        val placed = JournalChipLayout.place(listOf(request(370f)), spec).single()

        assertEquals(-1, placed.slot.side)
        assertTrue(placed.box.right <= 400f)
    }

    @Test
    fun aChipKeepsItsSpotWhileItStaysFree() {
        // At x 330 the right side would just fit, but last frame the chip hung left; it stays.
        val kept = JournalChipSlot(side = -1, lift = 0, nudge = 0)

        val placed = JournalChipLayout.place(listOf(request(300f, previous = kept)), spec).single()

        assertEquals(kept, placed.slot)
    }

    @Test
    fun aChipWhoseEntryHasLeftTheScreenRidesOffWithTheChart() {
        // Its entry is 30px past the left edge; the chip stays beside it instead of being
        // pushed back onto the screen.
        val placed = JournalChipLayout.place(listOf(request(-30f)), spec).single()

        assertEquals(JournalChipSlot.Preferred, placed.slot)
    }

    @Test
    fun withNoRoomLeftAChipOverlapsAndIsMarkedCrowded() {
        val tight = spec.copy(maxX = 120f, minTop = 190f, maxTop = 210f, maxNudge = 0)

        val placements = JournalChipLayout.place(listOf(request(10f), request(12f), request(14f)), tight)

        assertTrue(placements.any { it.crowded })
    }

    @Test
    fun aChipScrollingInDoesNotPushAChipAlreadyOnScreen() {
        // The later chip had the earlier chip's spot last frame; the earlier one is new.
        val onScreen = request(110f, previous = JournalChipSlot.Preferred)
        val scrollingIn = request(100f)

        val placements = JournalChipLayout.place(listOf(scrollingIn, onScreen), spec)

        assertEquals(JournalChipSlot.Preferred, placements[1].slot)
        assertNoOverlaps(placements)
    }

    // --- Piles ---

    @Test
    fun repeatsTuckBehindTheirTwinInsteadOfClimbing() {
        // A loop's 0,2 U doses five minutes apart, with every row above taken off the chart.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val placements = JournalChipLayout.place((0 until 3).map { request(100f + it * 20f, stackKey = "0,2") }, tight)

        assertTrue(placements.none { it.crowded })
        assertEquals(0, placements[0].depth)
        assertTrue(placements.all { it.front == 0 })
        assertEquals(listOf(0, 1, 2), placements.map { it.depth })
        // Each one's edge still shows.
        for (i in placements.indices) for (j in placements.indices) {
            if (i < j) {
                val a = placements[i].box
                val b = placements[j].box
                assertTrue(kotlin.math.abs(a.left - b.left) >= 8f || kotlin.math.abs(a.top - b.top) >= 8f)
            }
        }
    }

    @Test
    fun aUniqueValueGetsAFreeSpotBeforeRepeatsDo() {
        // The 3 U bolus comes last in time but claims its own spot first; the two 0,2 U
        // doses either side of it make do by tucking.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f, maxNudge = 1)
        val placements = JournalChipLayout.place(
            listOf(
                request(100f, stackKey = "0,2"),
                request(110f, stackKey = "0,2"),
                request(105f, stackKey = "3")
            ),
            tight
        )

        assertTrue(!placements[2].crowded)
        assertEquals(0, placements[2].depth)
        for (i in 0..1) assertTrue(!overlapping(placements[i].box, placements[2].box))
    }

    @Test
    fun differentValuesNeverTuckBehindEachOther() {
        val placements = JournalChipLayout.place(
            listOf(request(100f, stackKey = "0,2"), request(105f, stackKey = "0,3")),
            stacking
        )

        assertNoOverlaps(placements)
    }

    @Test
    fun aPileHoldsOnlyAFew() {
        val tight = stacking.copy(minTop = 190f, maxTop = 300f, maxStack = 2)
        val placements = JournalChipLayout.place((0 until 3).map { request(100f + it * 10f, stackKey = "0,2") }, tight)

        assertTrue(placements.groupBy { it.front }.values.all { it.size <= 2 })
    }

    @Test
    fun aFoldedChipHidesBehindTheChipItFoldsInto() {
        val placements = JournalChipLayout.place(listOf(request(100f), request(100.5f, foldInto = 0)), stacking)

        assertTrue(placements[1].folded)
        assertEquals(0, placements[1].front)
        assertEquals(placements[0].box, placements[1].box)
        assertEquals(listOf(listOf(0, 1)), JournalChipLayout.piles(placements, 12f, 8f).map { it.toList() })
    }

    @Test
    fun anEdgePeekingOutIsNotCountedAsHidden() {
        // The twin sits 75px across, tucked 5px under its front, so its label shows.
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val placements = JournalChipLayout.place(listOf(request(100f, stackKey = "0,2"), request(175f, stackKey = "0,2")), tight)

        assertEquals(0, placements[1].front)
        assertTrue(JournalChipLayout.piles(placements, 12f, 8f).isEmpty())
    }

    @Test
    fun aHiddenTwinFormsAPileWithItsFront() {
        val tight = stacking.copy(minTop = 190f, maxTop = 210f)
        val placements = JournalChipLayout.place(listOf(request(100f, stackKey = "0,2"), request(110f, stackKey = "0,2")), tight)

        val piles = JournalChipLayout.piles(placements, 12f, 8f)

        assertEquals(listOf(listOf(0, 1)), piles.map { it.toList() })
    }
}
