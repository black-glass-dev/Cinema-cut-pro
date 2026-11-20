package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/** Brand and neutral palette shared by the home screen and editor chrome. */
object CinemaColors {
    val Red = Color(0xFFFF3B30)
    val Ink = Color(0xFF1A1A1E)
    val Page = Color(0xFFF2F2F4)
    val LightGray = Color(0xFFE4E4E8)
    val Button = Color(0xFF2B2B30)
    val Muted = Color(0xFF92929A)
    val SecondaryText = Color(0xFF595961)
    val Divider = Color(0xFFD8D8DE)
}

object EditorColors {
    val BackgroundDark = CinemaColors.Page
    val SurfaceDark = Color.White
    val SurfaceVariantDark = CinemaColors.LightGray
    val AccentPrimary = CinemaColors.Red
    val AccentSecondary = Color(0xFF36363B)
    val TextPrimary = CinemaColors.Ink
    val TextSecondary = Color(0xFF66666E)
    val TextDisabled = Color(0xFF92929A)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFCF2E25)
    val Success = Color(0xFF008A52)
    val TrackVideo = Color(0xFF303035)
    val TrackAudio = Color(0xFF3E7854)
    val TrackAdjustment = Color(0xFF783E6C)
    val TimelineBackground = CinemaColors.Ink
    val Playhead = CinemaColors.Red
}
