package tk.glucodata.ui

/** A chip's label pill on the chart, in pixels. */
internal data class JournalChipBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Where one chip sits relative to its entry: which side, how far lifted or dropped, how far pushed out. */
internal data class JournalChipSlot(val side: Int, val lift: Int, val nudge: Int) {
    companion object {
        val Preferred = JournalChipSlot(side = 1, lift = 0, nudge = 0)
    }
}

/**
 * One chip to place: its entry's x, the top it would like, its width, and where it sat last
 * frame. Chips with the same [stackKey], such as a loop's repeated doses of one size, may tuck
 * behind one another. A chip with [foldInto] set is too close to that earlier chip to tell
 * apart on screen, so it hides behind it rather than taking a spot of its own.
 */
internal data class JournalChipRequest(
    val anchorX: Float,
    val baseTop: Float,
    val width: Float,
    val previous: JournalChipSlot?,
    val stackKey: Any? = null,
    val foldInto: Int? = null
)

/** How far each member of an opened pile moves, as `dx` and `dy` pairs, and which found no clear spot. */
internal class JournalChipSpread(val shifts: Array<FloatArray>, val stuck: BooleanArray) {
    val stuckCount: Int get() = stuck.count { it }
}

/**
 * The spot a chip was given. [front] is the chip it sits behind, or its own index when nothing
 * covers it; [depth] is how many layers down it sits, 0 being on top. [crowded] chips overlap
 * because no spot was free; [folded] ones hide wholly behind their front.
 */
internal data class JournalChipPlacement(
    val slot: JournalChipSlot,
    val box: JournalChipBox,
    val crowded: Boolean,
    val front: Int,
    val depth: Int,
    val folded: Boolean = false
)

/**
 * Lays journal chips out on the chart so they use the room around them instead of piling up.
 *
 * Each chip prefers to hang to the right of its entry at its own height. When that spot is
 * taken, it tries the other side of its entry, lifting or dropping by whole rows, and
 * nudging further out sideways, and takes the cheapest spot that is free. A chip keeps last
 * frame's spot while it stays free and on screen, so panning never reshuffles the chips;
 * the chart forgets those spots when the zoom settles, so the layout then tidies up.
 *
 * Chips whose value appears only once nearby claim the free room first. A chip repeating a
 * neighbour's value may then tuck behind it, a little offset so its edge still shows, rather
 * than climbing the chart; a pile holds a few at most. Only when no spot is free does a chip
 * overlap a different one. Piles whose chips hide a label spread apart, in whatever direction
 * has room, while tapped or hovered.
 */
internal object JournalChipLayout {

    /** Geometry and limits for [place], in pixels. */
    internal data class Spec(
        val chipHeight: Float,
        val sideOffset: Float,
        val rowStep: Float,
        val nudgeStep: Float,
        val gap: Float,
        val minX: Float,
        val maxX: Float,
        val minTop: Float,
        val maxTop: Float,
        val maxLift: Int = 10,
        val maxDrop: Int = 3,
        val maxNudge: Int = 4,
        /** How far apart, across or down, chips tucked together must sit so each one's edge shows. */
        val peek: Float = 0f,
        /** How close two chips with the same value must be for either to count as a repeat. */
        val repeatReach: Float = 0f,
        /** The most chips one pile holds, its front included. */
        val maxStack: Int = 4
    )

    // Costs, in rows of movement: a lift is the yardstick, a drop costs a little more, the
    // other side of the entry a little less, and a nudge sideways costs by distance. Any
    // part of a chip hanging off the screen costs heavily. A chip pushed off its last spot
    // still leans toward it, should that spot come back into reach.
    private const val LIFT_COST = 1f
    private const val DROP_COST = 1.3f
    private const val OTHER_SIDE_COST = 0.8f
    private const val NUDGE_COST_PER_ROW = 1.2f
    private const val OFF_SCREEN_COST_PER_ROW = 3f
    private const val KEEP_DISCOUNT = 1f
    // Tucking behind a twin is cheaper than climbing a row, but a free spot beside it still wins.
    private const val TUCK_COST = 0.4f
    private const val CLEAR = -1

    /**
     * Places [requests], which come in time order. Chips keeping last frame's spot go first,
     * then chips that had to leave theirs, then chips new to the chart, so nothing on screen
     * is pushed about by a chip scrolling in. Within each pass, chips whose value is unique
     * nearby go before repeats, and earlier entries before later ones. [obstacles], such as the
     * entries' own dots, are kept clear like placed chips. The result lines up with [requests].
     */
    fun place(
        requests: List<JournalChipRequest>,
        spec: Spec,
        obstacles: List<JournalChipBox> = emptyList()
    ): List<JournalChipPlacement> {
        val layout = Layout(requests, spec)
        obstacles.forEach { layout.grid.add(it, OBSTACLE) }
        val slots = slotsFor(spec)
        val folded = requests.indices.filter { requests[it].foldInto != null }.toHashSet()
        // First every chip that can keep last frame's spot does, outright, while that spot is
        // still open to it and on screen, or while its entry has yet to scroll in from the
        // right, so it slides in steadily rather than hunting for a spot each frame. Only the
        // chips that must move look for a new spot, and only among the open ones, so one
        // chip's move can never push another about.
        requests.forEachIndexed { index, request ->
            if (index in folded) return@forEachIndexed
            val previous = request.previous ?: return@forEachIndexed
            val box = boxFor(request, previous, spec) ?: return@forEachIndexed
            if (request.anchorX <= spec.maxX && offScreenOf(request, box, spec) != 0f) return@forEachIndexed
            val pile = layout.fitOf(index, box) ?: return@forEachIndexed
            layout.commit(index, previous, box, pile)
        }
        val displaced = requests.indices.filter { it !in folded && layout.placements[it] == null && requests[it].previous != null }
        val fresh = requests.indices.filter { it !in folded && requests[it].previous == null }
        val order = displaced.sortedBy { layout.repeats[it] } + fresh.sortedBy { layout.repeats[it] }
        // Once the chart is this full, so many chips have nowhere free that weighing every
        // overlap for each would stall panning; later ones still take any free spot there is,
        // but with none, overlap at the nearest spot on the chart without weighing how much.
        var crowdedCount = 0
        order.forEach { index ->
            val saturated = crowdedCount >= CROWDED_SEARCH_BUDGET
            val request = requests[index]
            var bestSlot: JournalChipSlot? = null
            var bestBox: JournalChipBox? = null
            var bestPile = CLEAR
            var bestCost = Float.POSITIVE_INFINITY
            fun consider(slot: JournalChipSlot) {
                val box = boxFor(request, slot, spec) ?: return
                if (leavesChart(request, box, spec)) return
                var cost = costOf(request, slot, box, spec)
                if (cost >= bestCost) return
                val pile = layout.fitOf(index, box) ?: return
                if (pile != CLEAR) cost += TUCK_COST
                if (cost < bestCost) {
                    bestSlot = slot
                    bestBox = box
                    bestPile = pile
                    bestCost = cost
                }
            }
            // Last frame's spot first: it is usually still open and still the cheapest.
            request.previous?.let(::consider)
            // Then the rest, nearest first. Going off screen or tucking only adds cost, so once
            // a spot's base cost alone is no better than the best found, nothing further can be.
            for ((slot, baseCost) in slots) {
                if (baseCost >= bestCost) break
                if (slot != request.previous) consider(slot)
            }
            val slot = bestSlot
            val box = bestBox
            if (slot != null && box != null) {
                layout.commit(index, slot, box, bestPile)
            } else {
                crowdedPlacement(index, request, slots, spec, layout, weighOverlap = !saturated)
                crowdedCount++
            }
        }
        // A folded chip hides wholly behind the chip it folds into, one layer under that pile.
        folded.sorted().forEach { index ->
            val host = layout.placements[requests[index].foldInto!!] ?: return@forEach
            val front = host.front
            layout.placements[index] = JournalChipPlacement(
                slot = host.slot,
                box = host.box,
                crowded = false,
                front = front,
                depth = layout.pileSizes[front],
                folded = true
            )
            layout.pileSizes[front]++
        }
        return layout.placements.map { it!! }
    }

    private const val OBSTACLE = -1

    /** The placements made so far, and where they sit. */
    private class Layout(val requests: List<JournalChipRequest>, val spec: Spec) {
        val grid = BoxGrid(columnWidth = (spec.rowStep * 2f).coerceAtLeast(1f))
        val placements = arrayOfNulls<JournalChipPlacement>(requests.size)
        val pileSizes = IntArray(requests.size)
        val repeats = repeatsOf(requests, spec.repeatReach)

        /**
         * Whether [box] is open to chip [index]: null if it is blocked, [CLEAR] if it touches no
         * chip, or the front of the pile it would tuck into. Only a repeat may tuck, only behind
         * chips with its own value, only into a single pile that has room, overlapping that
         * pile's front, and only far enough from each chip there that its edge still shows.
         */
        fun fitOf(index: Int, box: JournalChipBox): Int? {
            val key = requests[index].stackKey
            val mayTuck = key != null && repeats[index]
            var pile = CLEAR
            var touchesFront = false
            grid.forEachNear(box, spec.gap) { owner, other ->
                if (!within(box, other, spec.gap)) return@forEachNear
                if (!mayTuck || owner == OBSTACLE || requests[owner].stackKey != key) return null
                // Twins may sit closer than the usual gap.
                if (!within(box, other, 0f)) return@forEachNear
                if (kotlin.math.abs(box.left - other.left) < spec.peek && kotlin.math.abs(box.top - other.top) < spec.peek) return null
                val ownerPile = placements[owner]!!.front
                if (pile != CLEAR && pile != ownerPile) return null
                pile = ownerPile
                if (owner == ownerPile) touchesFront = true
            }
            if (pile == CLEAR) return CLEAR
            if (!touchesFront || pileSizes[pile] >= spec.maxStack) return null
            return pile
        }

        fun commit(index: Int, slot: JournalChipSlot, box: JournalChipBox, pile: Int, crowded: Boolean = false, depth: Int? = null) {
            val front = if (pile == CLEAR) index else pile
            placements[index] = JournalChipPlacement(
                slot = slot,
                box = box,
                crowded = crowded,
                front = front,
                depth = depth ?: if (pile == CLEAR) 0 else pileSizes[front]
            )
            pileSizes[front]++
            grid.add(box, index)
        }
    }

    // A chip repeats when another with the same value sits within [reach] of it.
    private fun repeatsOf(requests: List<JournalChipRequest>, reach: Float): BooleanArray {
        val repeats = BooleanArray(requests.size)
        requests.indices
            .filter { requests[it].stackKey != null }
            .groupBy { requests[it].stackKey }
            .values
            .forEach { same ->
                val byX = same.sortedBy { requests[it].anchorX }
                for (i in 1 until byX.size) {
                    if (requests[byX[i]].anchorX - requests[byX[i - 1]].anchorX <= reach) {
                        repeats[byX[i]] = true
                        repeats[byX[i - 1]] = true
                    }
                }
            }
        return repeats
    }

    private fun within(a: JournalChipBox, b: JournalChipBox, gap: Float): Boolean =
        a.left - gap < b.right && b.left < a.right + gap && a.top - gap < b.bottom && b.top < a.bottom + gap

    // With no open spot, the chip overlaps: of the nearest spots, and last frame's, it takes
    // the one that hides the least, cost included. Last frame's spot is favoured strongly, so
    // a crowded chip holds still while panning instead of hopping between equal overlaps.
    // Without [weighOverlap], it takes the cheapest of those spots by cost alone, which still
    // keeps it on the chart. It goes behind what it overlaps, joining the pile of the chip it
    // covers most.
    private fun crowdedPlacement(
        index: Int,
        request: JournalChipRequest,
        slots: List<Pair<JournalChipSlot, Float>>,
        spec: Spec,
        layout: Layout,
        weighOverlap: Boolean = true
    ) {
        var chosenSlot: JournalChipSlot? = null
        var chosenBox: JournalChipBox? = null
        var chosenCost = Float.POSITIVE_INFINITY
        fun consider(slot: JournalChipSlot, bonus: Float) {
            val box = boxFor(request, slot, spec) ?: return
            if (leavesChart(request, box, spec)) return
            var cost = costOf(request, slot, box, spec) - bonus
            if (cost >= chosenCost) return
            if (weighOverlap) {
                // Capped at the chip's own area: once a spot is wholly covered, more layers
                // under it hide nothing more, and must not outweigh hanging off the chart.
                val ownArea = (box.right - box.left + 2f * spec.gap) * (box.bottom - box.top + 2f * spec.gap)
                cost += minOf(layout.grid.overlapArea(box, spec.gap), ownArea) / (spec.chipHeight * spec.rowStep)
            }
            if (cost < chosenCost) {
                chosenSlot = slot
                chosenBox = box
                chosenCost = cost
            }
        }
        request.previous?.let { consider(it, CROWDED_KEEP_BONUS) }
        var tried = 0
        for ((slot, _) in slots) {
            if (tried >= CROWDED_CANDIDATES) break
            if (boxFor(request, slot, spec) == null) continue
            tried++
            consider(slot, 0f)
        }
        // With nothing in reach on the chart, the chip hangs on whichever side of its entry
        // leaves the chart least.
        val fallback = chosenSlot ?: listOf(JournalChipSlot.Preferred, JournalChipSlot(side = -1, lift = 0, nudge = 0))
            .minBy { offScreenOf(request, boxFor(request, it, spec, clamp = true)!!, spec) }
        val slot = fallback
        val box = chosenBox ?: boxFor(request, fallback, spec, clamp = true)!!
        var covered = OBSTACLE
        var coveredArea = 0f
        var depth = 0
        layout.grid.forEachNear(box, 0f) { owner, other ->
            if (owner == OBSTACLE) return@forEachNear
            val area = overlapOf(box, other)
            if (area <= 0f) return@forEachNear
            depth = maxOf(depth, layout.placements[owner]!!.depth + 1)
            if (area > coveredArea) {
                covered = owner
                coveredArea = area
            }
        }
        val pile = if (covered == OBSTACLE) CLEAR else layout.placements[covered]!!.front
        layout.commit(index, slot, box, pile, crowded = true, depth = depth)
    }

    private fun overlapOf(a: JournalChipBox, b: JournalChipBox): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width > 0f && height > 0f) width * height else 0f
    }

    private const val CROWDED_CANDIDATES = 24
    private const val CROWDED_SEARCH_BUDGET = 32
    private const val CROWDED_KEEP_BONUS = 2f

    // Every spot a chip may take, with its base cost, cheapest first.
    private fun slotsFor(spec: Spec): List<Pair<JournalChipSlot, Float>> {
        val slots = ArrayList<Pair<JournalChipSlot, Float>>()
        for (side in intArrayOf(1, -1)) {
            for (lift in -spec.maxDrop..spec.maxLift) {
                for (nudge in 0..spec.maxNudge) {
                    val slot = JournalChipSlot(side, lift, nudge)
                    slots.add(slot to baseCostOf(slot, spec))
                }
            }
        }
        // Stable, so equal costs keep this order: right before left, lifts before drops.
        return slots.sortedBy { it.second }
    }

    private fun boxFor(request: JournalChipRequest, slot: JournalChipSlot, spec: Spec, clamp: Boolean = false): JournalChipBox? {
        // A chip that wants to sit below the usual floor (insulin, under its curve) may.
        val maxTop = maxOf(spec.maxTop, request.baseTop)
        val base = request.baseTop.coerceIn(spec.minTop, maxTop)
        var top = base - slot.lift * spec.rowStep
        if (top < spec.minTop || top > maxTop) {
            if (!clamp) return null
            top = top.coerceIn(spec.minTop, maxTop)
        }
        val push = spec.sideOffset + slot.nudge * spec.nudgeStep
        val left = if (slot.side > 0) request.anchorX + push else request.anchorX - push - request.width
        return JournalChipBox(left, top, left + request.width, top + spec.chipHeight)
    }

    private fun baseCostOf(slot: JournalChipSlot, spec: Spec): Float {
        var cost = if (slot.lift >= 0) slot.lift * LIFT_COST else -slot.lift * DROP_COST
        if (slot.side < 0) cost += OTHER_SIDE_COST
        cost += slot.nudge * spec.nudgeStep / spec.rowStep * NUDGE_COST_PER_ROW
        return cost
    }

    private fun costOf(request: JournalChipRequest, slot: JournalChipSlot, box: JournalChipBox, spec: Spec): Float {
        var cost = baseCostOf(slot, spec)
        cost += offScreenOf(request, box, spec) / spec.rowStep * OFF_SCREEN_COST_PER_ROW
        if (slot == request.previous) cost -= KEEP_DISCOUNT
        return cost
    }

    // A chip whose entry is on screen stays wholly on the chart; one whose entry has left, or
    // has yet to arrive, may hang off with it.
    private fun leavesChart(request: JournalChipRequest, box: JournalChipBox, spec: Spec): Boolean =
        request.anchorX >= spec.minX && request.anchorX <= spec.maxX && offScreenOf(request, box, spec) > 0f

    // How far a chip hangs off the screen, as far as it matters. Past the right edge always
    // counts, so a chip scrolling in from the right arrives already hung to the left of its
    // entry instead of flipping over once its entry appears. Past the left edge only counts
    // while the entry is on screen: a chip whose entry has left rides off with it rather
    // than being pinned to the edge.
    private fun offScreenOf(request: JournalChipRequest, box: JournalChipBox, spec: Spec): Float {
        var offScreen = (box.right - spec.maxX).coerceAtLeast(0f)
        if (request.anchorX >= spec.minX) offScreen += (spec.minX - box.left).coerceAtLeast(0f)
        return offScreen
    }

    /** Placed boxes bucketed into columns, so a candidate is only checked against its neighbours. */
    private class BoxGrid(private val columnWidth: Float) {
        val boxes = ArrayList<JournalChipBox>()
        val owners = ArrayList<Int>()
        private val columns = HashMap<Int, MutableList<Int>>()

        fun columnsOf(left: Float, right: Float): IntRange =
            kotlin.math.floor(left / columnWidth).toInt()..kotlin.math.floor(right / columnWidth).toInt()

        fun add(box: JournalChipBox, owner: Int) {
            val entry = boxes.size
            boxes.add(box)
            owners.add(owner)
            for (column in columnsOf(box.left, box.right)) columns.getOrPut(column) { ArrayList() }.add(entry)
        }

        /** Calls [action] for every placed box in the columns within [gap] of [box]; a wide one may come more than once. */
        inline fun forEachNear(box: JournalChipBox, gap: Float, action: (owner: Int, other: JournalChipBox) -> Unit) {
            for (column in columnsOf(box.left - gap, box.right + gap)) {
                val entries = columnEntries(column) ?: continue
                for (i in entries.indices) {
                    val entry = entries[i]
                    action(owners[entry], boxes[entry])
                }
            }
        }

        fun columnEntries(column: Int): List<Int>? = columns[column]

        /** Area of [box], widened by [gap], that already-placed chips cover. */
        fun overlapArea(box: JournalChipBox, gap: Float): Float {
            var area = 0f
            val range = columnsOf(box.left - gap, box.right + gap)
            for (column in range) {
                columns[column]?.forEach { entry ->
                    val other = boxes[entry]
                    // A wide chip sits in several columns; count it once, in the first shared one.
                    val firstShared = maxOf(kotlin.math.floor(other.left / columnWidth).toInt(), range.first)
                    if (column != firstShared) return@forEach
                    val width = minOf(box.right + gap, other.right) - maxOf(box.left - gap, other.left)
                    val height = minOf(box.bottom + gap, other.bottom) - maxOf(box.top - gap, other.top)
                    if (width > 0f && height > 0f) area += width * height
                }
            }
            return area
        }
    }

    /**
     * The piles in which some chip's label is hidden: each starts with its front, then the
     * chips hidden behind it, top layer first. A chip counts as hidden when it is folded, or
     * when a chip above it in its pile covers more than [minOverlapX] across and [minOverlapY]
     * down, so an edge that merely peeks out from behind its twin does not count.
     */
    fun piles(placements: List<JournalChipPlacement>, minOverlapX: Float, minOverlapY: Float): List<IntArray> {
        val members = placements.indices.groupBy { placements[it].front }
        val piles = ArrayList<IntArray>()
        members.forEach { (front, pile) ->
            if (pile.size < 2) return@forEach
            val hidden = pile.filter { index ->
                if (index == front) return@filter false
                val placement = placements[index]
                placement.folded || pile.any { other ->
                    val above = placements[other]
                    other != index && above.depth < placement.depth &&
                        minOf(above.box.right, placement.box.right) - maxOf(above.box.left, placement.box.left) > minOverlapX &&
                        minOf(above.box.bottom, placement.box.bottom) - maxOf(above.box.top, placement.box.top) > minOverlapY
                }
            }
            if (hidden.isEmpty()) return@forEach
            piles.add((listOf(front) + hidden.sortedWith(compareBy({ placements[it].depth }, { it }))).toIntArray())
        }
        return piles
    }

    /**
     * Offsets that move each of [members] the shortest way, in any direction, to a spot clear
     * of every other chip on the chart.
     *
     * Members are placed in the order given, front first. A chip stays put when no member
     * placed before it is in the way; chips outside the group were already there, so staying
     * makes nothing worse. Otherwise it tries rings of candidate spots around its own position,
     * nearest first, [ringStepPx] apart out to [maxRadiusPx], and takes the first that keeps
     * [gapPx] from every chip already placed and every chip outside the group, and stays inside
     * [minX]..[maxX] × [minTop]..[maxTop]. A chip with no such spot in reach stays where it is
     * and is marked stuck, so the chart can say so and offer a closer zoom. The result lines up
     * with [members].
     */
    fun spread(
        boxes: List<JournalChipBox>,
        members: IntArray,
        minX: Float,
        maxX: Float,
        minTop: Float,
        maxTop: Float,
        gapPx: Float,
        ringStepPx: Float,
        maxRadiusPx: Float
    ): JournalChipSpread {
        val shifts = Array(members.size) { FloatArray(2) }
        val stuck = BooleanArray(members.size)
        val memberSet = members.toHashSet()
        val placed = ArrayList<JournalChipBox>(boxes.size)
        boxes.indices.filter { it !in memberSet }.mapTo(placed) { boxes[it] }
        val placedMembers = ArrayList<JournalChipBox>(members.size)
        val candidates = candidateOffsets(ringStepPx, maxRadiusPx)
        members.indices.forEach { slot ->
            val box = boxes[members[slot]]
            val width = box.right - box.left
            val height = box.bottom - box.top
            if (placedMembers.none { within(box, it, gapPx) }) {
                placedMembers.add(box)
                placed.add(box)
                return@forEach
            }
            // Only chips within reach of a candidate spot can block it.
            val reach = maxRadiusPx + gapPx
            val nearby = placed.filter { other ->
                other.right > box.left - reach && other.left < box.right + reach &&
                    other.bottom > box.top - reach && other.top < box.bottom + reach
            }
            val spot = candidates.firstOrNull { (dx, dy) ->
                val left = box.left + dx
                val top = box.top + dy
                left >= minX && left + width <= maxX && top >= minTop && top <= maxTop &&
                    nearby.none { other ->
                        left < other.right + gapPx && other.left < left + width + gapPx &&
                            top < other.bottom + gapPx && other.top < top + height + gapPx
                    }
            }
            if (spot == null) stuck[slot] = true
            val (dx, dy) = spot ?: (0f to 0f)
            shifts[slot][0] = dx
            shifts[slot][1] = dy
            val moved = JournalChipBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            placedMembers.add(moved)
            placed.add(moved)
        }
        return JournalChipSpread(shifts, stuck)
    }

    private const val RING_DIRECTIONS = 24

    // Directions around a ring, closest to vertical first and up before down: at equal
    // distance a chip stays over its own entry, and only goes sideways when it has to.
    private val RING_ANGLES: List<Double> = (0 until RING_DIRECTIONS)
        .map { step -> step * 2 * Math.PI / RING_DIRECTIONS }
        .sortedWith(compareBy<Double>({ kotlin.math.abs(kotlin.math.cos(it)).roundTo(6) }, { -kotlin.math.sin(it).roundTo(6) }))

    private fun Double.roundTo(places: Int): Double {
        val scale = Math.pow(10.0, places.toDouble())
        return Math.round(this * scale) / scale
    }

    // Rings of spots around a chip, nearest first.
    private fun candidateOffsets(ringStepPx: Float, maxRadiusPx: Float): List<Pair<Float, Float>> {
        val offsets = ArrayList<Pair<Float, Float>>()
        offsets.add(0f to 0f)
        if (ringStepPx <= 0f) return offsets
        var radius = ringStepPx
        while (radius <= maxRadiusPx) {
            RING_ANGLES.forEach { angle ->
                // Screen y grows downward, so a negative sine is up. Rounded to a hundredth of
                // a pixel, so straight down is exactly 0 across and passes the left-edge check.
                offsets.add((radius * kotlin.math.cos(angle)).roundTo(2).toFloat() to (-radius * kotlin.math.sin(angle)).roundTo(2).toFloat())
            }
            radius += ringStepPx
        }
        return offsets
    }
}
