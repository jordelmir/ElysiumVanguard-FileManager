package com.elysium.vanguard.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * SECTION COLOR MANAGER
 * Controls the thematic accent color for each major app module.
 *
 * NOTE (PHASE 10.9): defaults are the legacy [TitanColors] values
 * (compile-time constants). This object is not @Composable, so it
 * can't read from [GlobalColors] (which requires a composable
 * context). The defaults are static; once a user picks an accent
 * via the ColorSelectionDialog, the live value flows from there
 * and overrides the static default.
 */
object SectionColorManager {
    var dashboardAccent by mutableStateOf(TitanColors.NeonCyan)
    var galleryAccent by mutableStateOf(TitanColors.QuantumPink)
    var musicAccent by mutableStateOf(TitanColors.RadioactiveGreen)
    var fileAccent by mutableStateOf(TitanColors.NeonCyan)
    
    var isMulticolor by mutableStateOf(false)

    fun updateAccent(section: String, color: Color, applyToAll: Boolean = false) {
        isMulticolor = false // Picking a solid color disables multicolor
        if (applyToAll) {
            dashboardAccent = color
            galleryAccent = color
            musicAccent = color
            fileAccent = color
        } else {
            when (section.uppercase()) {
                "DASHBOARD" -> dashboardAccent = color
                "GALLERY" -> galleryAccent = color
                "MUSIC" -> musicAccent = color
                "FILEMANAGER" -> fileAccent = color
            }
        }
    }
    
    fun enableMulticolor() {
        isMulticolor = true
    }
}
