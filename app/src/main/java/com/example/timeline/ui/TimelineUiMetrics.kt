package com.example.timeline.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TimelineUiMetrics {
    private val _lastLayoutTimeMs = MutableStateFlow(0L)
    val lastLayoutTimeMs = _lastLayoutTimeMs.asStateFlow()

    fun reportLayoutTime(timeMs: Long) {
        _lastLayoutTimeMs.value = timeMs
    }
}
