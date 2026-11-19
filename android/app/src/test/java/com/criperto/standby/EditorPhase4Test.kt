package com.criperto.standby

import com.criperto.standby.core.SceneObject
import com.criperto.standby.editor.HistoryManager
import com.criperto.standby.editor.SceneObjectOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPhase4Test {

    @Test
    fun historyManagerUndoRedoCapAndTransactions() {
        val history = HistoryManager<Int>(maxSteps = 5)
        var current = 0

        for (step in 1..8) {
            history.recordBeforeChange(current)
            current = step
        }
        // Capped at 5 undo steps
        assertEquals(5, history.undoCount)
        assertEquals(0, history.redoCount)

        // Undo 2 times: 8 -> 7 -> 6
        current = history.undo(current)!!
        assertEquals(7, current)
        current = history.undo(current)!!
        assertEquals(6, current)
        assertEquals(3, history.undoCount)
        assertEquals(2, history.redoCount)

        // Redo once: 6 -> 7
        current = history.redo(current)!!
        assertEquals(7, current)

        // Drag transaction coalesces multiple intermediate values into 1 step
        history.beginTransaction(current)
        current = 100
        current = 200
        current = 300
        history.commitTransaction(current)
        assertEquals(5, history.undoCount)

        val afterUndoDrag = history.undo(current)
        assertEquals(7, afterUndoDrag)
    }

    @Test
    fun sceneObjectCrudZOrderAndGroupUngroup() {
        val c1 = SceneObjectOps.createDefaultObject("clock_hours", emptyList())
        val c2 = SceneObjectOps.createDefaultObject("clock_minutes", listOf(c1)).copy(
            posXRatio = 0.7,
            posYRatio = 0.6,
        )
        val list = listOf(c1, c2)
        assertEquals(1, c1.zIndex)
        assertEquals(2, c2.zIndex)

        // Move c1 up in Z-order
        val reordered = SceneObjectOps.moveZOrder(list, c1.id, +1)
        val c1After = reordered.first { it.id == c1.id }
        val c2After = reordered.first { it.id == c2.id }
        assertTrue(c1After.zIndex > c2After.zIndex)

        // Group c1 and c2
        val (groupedList, groupId) = SceneObjectOps.groupSelection(
            reordered,
            setOf(c1.id, c2.id),
            screenW = 1920.0,
            screenH = 1080.0,
        )
        assertNotNull(groupId)
        assertEquals(3, groupedList.size)
        val gObj = groupedList.first { it.id == groupId }
        assertEquals("group", gObj.objType)
        assertEquals(2, gObj.childrenIds.size)
        assertTrue(groupedList.filter { it.id != groupId }.all { it.groupId == groupId })

        // Ungroup restores children to root
        val ungrouped = SceneObjectOps.ungroupSelection(groupedList, groupId!!, 1920.0, 1080.0)
        assertEquals(2, ungrouped.size)
        assertTrue(ungrouped.all { it.groupId == null })

        // Duplicate & Delete
        val (withDup, dupIds) = SceneObjectOps.duplicateSelected(ungrouped, setOf(c1.id))
        assertEquals(3, withDup.size)
        assertEquals(1, dupIds.size)

        val afterDelete = SceneObjectOps.deleteSelected(withDup, dupIds)
        assertEquals(2, afterDelete.size)
        assertNull(afterDelete.firstOrNull { it.id in dupIds })
    }

    @Test
    fun pureVisualBoxAccountsForPivotOffsetAndViewportScale() {
        val obj = SceneObject(
            id = "test_piv",
            posXRatio = 0.5,
            posYRatio = 0.5,
            baseWidth = 200.0,
            baseHeight = 100.0,
            pivotX = 0.0,
            pivotY = 0.0,
            rotation = 0.0,
        )
        // Reference 1920x1080 -> vScale = 1.0
        val boxRef = SceneObjectOps.computePureVisualBox(obj, listOf(obj), 1920.0, 1080.0)
        assertEquals(960.0, boxRef.anchorX, 0.01)
        assertEquals(540.0, boxRef.anchorY, 0.01)
        assertEquals(1060.0, boxRef.visualCenterX, 0.01)
        assertEquals(590.0, boxRef.visualCenterY, 0.01)

        // Half resolution 960x540 -> vScale = 0.5 -> width=100, height=50
        val boxHalf = SceneObjectOps.computePureVisualBox(obj, listOf(obj), 960.0, 540.0)
        assertEquals(480.0, boxHalf.anchorX, 0.01)
        assertEquals(270.0, boxHalf.anchorY, 0.01)
        assertEquals(530.0, boxHalf.visualCenterX, 0.01)
        assertEquals(295.0, boxHalf.visualCenterY, 0.01)
    }
}
