package com.oriontv.ui.components

import com.oriontv.ui.theme.OrionColors
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryChipTest {

    @Test
    fun `selected chip is solid brand with white text`() {
        assertEquals(
            ChipColors(OrionColors.Brand, OrionColors.Text),
            categoryChipColors(selected = true, focused = false),
        )
        assertEquals(
            ChipColors(OrionColors.Brand, OrionColors.Text),
            categoryChipColors(selected = true, focused = true),
        )
    }

    @Test
    fun `focused unselected chip is tinted brand with white text not solid brand`() {
        val colors = categoryChipColors(selected = false, focused = true)
        assertEquals(OrionColors.Text, colors.text)
        assertEquals(OrionColors.Brand.copy(alpha = 0.3f), colors.background)
    }

    @Test
    fun `plain chip is surface with secondary text`() {
        assertEquals(
            ChipColors(OrionColors.Surface, OrionColors.TextSecondary),
            categoryChipColors(selected = false, focused = false),
        )
    }
}
