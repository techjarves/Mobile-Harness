package com.jarves.mh.ui

import com.jarves.mh.R
import com.jarves.mh.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderLabelsTest {
    @Test
    fun openCodeGoHasDedicatedTitleAndSubtitle() {
        assertEquals(R.string.provider_title_opencode_go, ProviderKind.OPENCODE_GO.titleRes())
        assertEquals(R.string.provider_subtitle_opencode_go, ProviderKind.OPENCODE_GO.subtitleRes())
    }
}
