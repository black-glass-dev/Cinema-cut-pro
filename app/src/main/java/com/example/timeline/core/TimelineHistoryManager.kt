package com.example.timeline.core

class TimelineHistoryManager(private val maxHistorySize: Int = 50) {
    private val undoStack = mutableListOf<TimelineCommand>()
    private val redoStack = mutableListOf<TimelineCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val currentHistoryLabel: String?
        get() = undoStack.lastOrNull()?.commandName

    val nextUndoCommand: TimelineCommand?
        get() = undoStack.lastOrNull()

    val nextRedoCommand: TimelineCommand?
        get() = redoStack.lastOrNull()

    fun pushCommand(command: TimelineCommand) {
        undoStack.add(command)
        if (undoStack.size > maxHistorySize) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(project: TimelineProject): TimelineResult {
        if (!canUndo) return TimelineResult(project, "Nothing to undo")
        val command = undoStack.last()
        val result = command.undo(project)
        if (result.isSuccess) {
            undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(command)
        }
        return result
    }

    fun redo(project: TimelineProject): TimelineResult {
        if (!canRedo) return TimelineResult(project, "Nothing to redo")
        val command = redoStack.last()
        val result = command.redo(project)
        if (result.isSuccess) {
            redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(command)
            if (undoStack.size > maxHistorySize) undoStack.removeAt(0)
        }
        return result
    }

    fun containsNativeTimelineCommands(): Boolean =
        undoStack.any { it is NativeTimelineHistoryCommand } ||
            redoStack.any { it is NativeTimelineHistoryCommand }

    fun clearNativeTimelineCommands() {
        undoStack.removeAll { it is NativeTimelineHistoryCommand }
        redoStack.removeAll { it is NativeTimelineHistoryCommand }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
