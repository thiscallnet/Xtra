package com.github.andreyasadchy.xtra.ui.player.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutEngineTest {
    private val viewports = listOf(
        320f to 180f,
        360f to 203f,
        393f to 221f,
        568f to 320f,
        640f to 360f,
        800f to 360f,
        852f to 393f,
        915f to 412f,
        960f to 600f,
    )

    @Test
    fun `default layouts stay inside safe viewport and use minimum hit targets`() {
        viewports.forEach { (width, height) ->
            listOf(HudOrientation.PORTRAIT, HudOrientation.LANDSCAPE).forEach { orientation ->
                val safe = HudRect(0f, 0f, width, height)
                val result = engine().resolve(safe, orientation, defaultProfile(), sizes(width, height))

                result.forEach { element ->
                    assertTrue("${width}x$height ${element.id} left", element.hitRect.left >= safe.left - .01f)
                    assertTrue("${width}x$height ${element.id} top", element.hitRect.top >= safe.top - .01f)
                    assertTrue("${width}x$height ${element.id} right", element.hitRect.right <= safe.right + .01f)
                    assertTrue("${width}x$height ${element.id} bottom", element.hitRect.bottom <= safe.bottom + .01f)
                    val spec = HudElementRegistry.get(element.id)
                    if (spec.isInteractive) {
                        assertTrue(element.hitRect.width >= spec.minimumHitSize.width - .01f)
                        assertTrue(element.hitRect.height >= spec.minimumHitSize.height - .01f)
                    }
                }
                result.forEachIndexed { index, element ->
                    result.drop(index + 1).forEach { other ->
                        assertTrue(
                            "${width}x$height $orientation overlap: ${element.id}/${other.id}",
                            !element.hitRect.overlaps(other.hitRect),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `default responsive top right controls follow width rules`() {
        val portraitNarrow = engine().resolve(HudRect(0f, 0f, 320f, 203f), HudOrientation.PORTRAIT, defaultProfile(), sizes(320f, 203f))
        val portraitWide = engine().resolve(HudRect(0f, 0f, 360f, 203f), HudOrientation.PORTRAIT, defaultProfile(), sizes(360f, 203f))
        val landscapeNarrow = engine().resolve(HudRect(0f, 0f, 568f, 320f), HudOrientation.LANDSCAPE, defaultProfile(), sizes(568f, 320f))
        val landscapeWide = engine().resolve(HudRect(0f, 0f, 640f, 360f), HudOrientation.LANDSCAPE, defaultProfile(), sizes(640f, 360f))

        assertTrue(portraitNarrow.none { it.id == HudElementId.FOLLOW })
        assertTrue(portraitWide.any { it.id == HudElementId.FOLLOW })
        assertTrue(landscapeNarrow.none { it.id == HudElementId.ASPECT_RATIO })
        assertTrue(landscapeWide.any { it.id == HudElementId.ASPECT_RATIO })
    }

    @Test
    fun `default portrait and landscape share geometry at the same viewport`() {
        viewports.forEach { (width, height) ->
            val safe = HudRect(0f, 0f, width, height)
            val portrait = engine().resolve(safe, HudOrientation.PORTRAIT, defaultProfile(), sizes(width, height))
            val landscape = engine().resolve(safe, HudOrientation.LANDSCAPE, defaultProfile(), sizes(width, height))
            assertEquals("$width x $height", portrait, landscape)
        }
    }

    @Test
    fun `play remains centered when seek controls scale`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val base = defaultProfile()
        val basePlay = engine().resolve(safe, HudOrientation.LANDSCAPE, base, sizes(800f, 360f))
            .first { it.id == HudElementId.PLAY_PAUSE }
        val rewindScaled = base.copy(
            placements = base.placements + (HudElementId.SEEK_BACK to HudPlacement(true, .5f, .5f, 1.75f)),
        )
        val forwardScaled = base.copy(
            placements = base.placements + (HudElementId.SEEK_FORWARD to HudPlacement(true, .5f, .5f, 1.75f)),
        )
        val rewindPlay = engine().resolve(safe, HudOrientation.LANDSCAPE, rewindScaled, sizes(800f, 360f))
            .first { it.id == HudElementId.PLAY_PAUSE }
        val forwardPlay = engine().resolve(safe, HudOrientation.LANDSCAPE, forwardScaled, sizes(800f, 360f))
            .first { it.id == HudElementId.PLAY_PAUSE }

        assertEquals(safe.centerX, basePlay.hitRect.centerX, .01f)
        assertEquals(basePlay.hitRect.centerX, rewindPlay.hitRect.centerX, .01f)
        assertEquals(basePlay.hitRect.centerX, forwardPlay.hitRect.centerX, .01f)
    }

    @Test
    fun `custom placement is independent from availability`() {
        val safe = HudRect(0f, 0f, 915f, 412f)
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            1f,
            HudElementId.entries.associateWith { id ->
                HudPlacement(true, .1f + id.ordinal * .03f, .2f, 1f)
            },
        )
        val all = engine().resolve(safe, HudOrientation.LANDSCAPE, profile, sizes(915f, 412f)).associateBy { it.id }
        val reduced = engine().resolve(
            safe,
            HudOrientation.LANDSCAPE,
            profile,
            sizes(915f, 412f),
            availability = (HudElementId.entries - setOf(HudElementId.CAPTIONS, HudElementId.CHAT)).toSet(),
        ).associateBy { it.id }

        all.forEach { (id, element) ->
            if (id != HudElementId.CAPTIONS && id != HudElementId.CHAT) {
                assertEquals(element.visualRect, reduced.getValue(id).visualRect)
                assertEquals(element.hitRect, reduced.getValue(id).hitRect)
            }
        }
    }

    @Test
    fun `sparse custom profiles inherit the shared responsive default`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val custom = HudProfile(
            HudProfileMode.CUSTOM,
            1f,
            mapOf(HudElementId.QUALITY to HudPlacement(true, .2f, .3f, 1f)),
        )
        val defaultResult = engine().resolve(
            safe,
            HudOrientation.LANDSCAPE,
            defaultProfile(),
            sizes(800f, 360f),
        ).associateBy { it.id }
        val customResult = engine().resolve(
            safe,
            HudOrientation.PORTRAIT,
            custom,
            sizes(800f, 360f),
        ).associateBy { it.id }

        assertEquals(defaultResult.getValue(HudElementId.FOLLOW), customResult.getValue(HudElementId.FOLLOW))
        assertEquals(.2f, customResult.getValue(HudElementId.QUALITY).visualRect.centerX / safe.width, .01f)
        assertEquals(.3f, customResult.getValue(HudElementId.QUALITY).visualRect.centerY / safe.height, .01f)
    }

    @Test
    fun `default timeline reserves its resolved 48dp visual band`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val result = engine().resolve(safe, HudOrientation.LANDSCAPE, defaultProfile(), sizes(800f, 360f))
            .associateBy { it.id }

        assertEquals(48f, result.getValue(HudElementId.TIMELINE).visualRect.height, .01f)
        assertTrue(
            result.getValue(HudElementId.VOLUME).hitRect.bottom <=
                result.getValue(HudElementId.TIMELINE).visualRect.top - HudDefaultLayout.SPACING + .01f,
        )
    }

    @Test
    fun `minimum and maximum profile scales remain finite and deterministic`() {
        val safe = HudRect(24f, 12f, 984f, 612f)
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            .85f,
            HudElementId.entries.associateWith { HudPlacement(true, .5f, .5f, .75f) },
        )
        val first = engine(rtl = true).resolve(safe, HudOrientation.LANDSCAPE, profile, sizes(safe.width, safe.height))
        val second = engine(rtl = true).resolve(safe, HudOrientation.LANDSCAPE, profile.copy(globalScale = 1.30f), sizes(safe.width, safe.height))
        assertEquals(first, engine(rtl = true).resolve(safe, HudOrientation.LANDSCAPE, profile, sizes(safe.width, safe.height)))
        assertTrue(first.all { it.effectiveScale.isFinite() })
        assertTrue(second.all { it.effectiveScale.isFinite() })
    }

    @Test
    fun `rtl default generation keeps top start metadata on the safe end`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val metadata = engine(rtl = true)
            .resolve(safe, HudOrientation.LANDSCAPE, defaultProfile(), sizes(800f, 360f))
            .first { it.id == HudElementId.STREAM_INFO }

        assertEquals(safe.right - HudDefaultLayout.NORMAL_EDGE_PADDING, metadata.visualRect.right, .01f)
        assertEquals(safe.top + HudDefaultLayout.NORMAL_EDGE_PADDING, metadata.visualRect.top, .01f)
    }

    @Test
    fun `semantic pivots and fixed controls do not depend on content size`() {
        val safe = HudRect(10f, 20f, 810f, 380f)
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            1f,
            mapOf(
                HudElementId.STREAM_INFO to HudPlacement(true, .15f, .2f, 1f),
                HudElementId.QUALITY to HudPlacement(true, .7f, .4f, 1f),
            ),
        )
        val compactMetadata = sizes(800f, 360f) + (HudElementId.STREAM_INFO to HudSize(120f, 48f))
        val expandedMetadata = compactMetadata + (HudElementId.STREAM_INFO to HudSize(280f, 76f))
        val compact = engine().resolve(safe, HudOrientation.LANDSCAPE, profile, compactMetadata).associateBy { it.id }
        val expanded = engine().resolve(safe, HudOrientation.LANDSCAPE, profile, expandedMetadata).associateBy { it.id }

        assertEquals(safe.left + safe.width * .15f, compact.getValue(HudElementId.STREAM_INFO).visualRect.left, .01f)
        assertEquals(compact.getValue(HudElementId.STREAM_INFO).visualRect.left, expanded.getValue(HudElementId.STREAM_INFO).visualRect.left, .01f)
        assertEquals(compact.getValue(HudElementId.STREAM_INFO).visualRect.top, expanded.getValue(HudElementId.STREAM_INFO).visualRect.top, .01f)
        assertEquals(72f, compact.getValue(HudElementId.QUALITY).visualRect.width, .01f)
        assertEquals(72f, expanded.getValue(HudElementId.QUALITY).visualRect.width, .01f)
    }

    @Test
    fun `compact metrics keep timeline at the safe bottom and honor cutouts`() {
        val safe = HudRect(28f, 16f, 876f, 237f)
        val result = engine().resolve(safe, HudOrientation.PORTRAIT, defaultProfile(), sizes(safe.width, safe.height))
            .associateBy { it.id }

        assertEquals(safe.bottom, result.getValue(HudElementId.TIMELINE).hitRect.bottom, .01f)
        assertEquals(safe.centerX, result.getValue(HudElementId.PLAY_PAUSE).hitRect.centerX, .01f)
        result.values.forEach { element ->
            assertTrue(element.hitRect.left >= safe.left - .01f)
            assertTrue(element.hitRect.right <= safe.right + .01f)
            assertTrue(element.hitRect.top >= safe.top - .01f)
            assertTrue(element.hitRect.bottom <= safe.bottom + .01f)
        }
    }

    @Test
    fun `compact edge controls yield to transport when cutouts narrow the safe width`() {
        listOf(
            HudRect(20f, 0f, 300f, 180f),
            HudRect(0f, 0f, 276f, 180f),
            HudRect(44f, 0f, 300f, 180f),
        ).forEach { safe ->
            listOf(false, true).forEach { rtl ->
                val result = engine(rtl = rtl).resolve(
                    safe,
                    HudOrientation.PORTRAIT,
                    defaultProfile(),
                    sizes(safe.width, safe.height),
                )
                result.forEachIndexed { index, element ->
                    result.drop(index + 1).forEach { other ->
                        assertTrue(
                            "${safe.width} compact rtl=$rtl overlap: ${element.id}/${other.id}",
                            !element.hitRect.overlaps(other.hitRect),
                        )
                    }
                }
                assertEquals(safe.centerX, result.first { it.id == HudElementId.PLAY_PAUSE }.hitRect.centerX, .01f)
            }
        }
    }

    @Test
    fun `metadata budget uses the same density aware top row math as resolution`() {
        val density = 2f
        val safe = HudRect(0f, 0f, 800f, 720f)
        val profile = PlayerHudDefaults.config().landscape
        val measured = sizes(safe.width, safe.height, density) +
            (HudElementId.STREAM_INFO to HudSize(700f, 120f))
        val layout = engine(density = density)
        val budget = layout.metadataWidthBudget(
            safe,
            HudOrientation.LANDSCAPE,
            profile,
            HudElementId.entries.toSet(),
        )
        val metadata = layout.resolve(safe, HudOrientation.LANDSCAPE, profile, measured)
            .first { it.id == HudElementId.STREAM_INFO }

        assertTrue(metadata.visualRect.width <= budget + .01f)
        assertEquals(budget, layout.metadataWidthBudget(safe, HudOrientation.LANDSCAPE, profile, HudElementId.entries.toSet()), .01f)
    }

    @Test
    fun `custom edge placement translates visual and hit rectangles together`() {
        val safe = HudRect(24f, 16f, 824f, 376f)
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            1.30f,
            mapOf(
                HudElementId.QUALITY to HudPlacement(true, 0f, 0f, 1.75f),
            ),
        )
        val element = engine().resolve(safe, HudOrientation.LANDSCAPE, profile, sizes(safe.width, safe.height))
            .single { it.id == HudElementId.QUALITY }

        assertTrue(element.visualRect.left >= safe.left - .01f)
        assertTrue(element.visualRect.top >= safe.top - .01f)
        assertTrue(element.visualRect.right <= safe.right + .01f)
        assertTrue(element.visualRect.bottom <= safe.bottom + .01f)
        assertTrue(element.hitRect.left >= safe.left - .01f)
        assertTrue(element.hitRect.top >= safe.top - .01f)
        assertEquals(element.visualRect.centerX, element.hitRect.centerX, .01f)
        assertEquals(element.visualRect.centerY, element.hitRect.centerY, .01f)
    }

    @Test
    fun `default top controls share the metadata top edge`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val result = engine().resolve(safe, HudOrientation.LANDSCAPE, defaultProfile(), sizes(800f, 360f))
            .associateBy { it.id }
        val metadataTop = result.getValue(HudElementId.STREAM_INFO).visualRect.top

        listOf(HudElementId.FOLLOW, HudElementId.QUALITY, HudElementId.ASPECT_RATIO).forEach { id ->
            assertEquals(metadataTop, result.getValue(id).visualRect.top, .01f)
        }
    }

    @Test
    fun `scaled timeline remains inside the safe viewport`() {
        val safe = HudRect(0f, 0f, 800f, 360f)
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            1.30f,
            mapOf(HudElementId.TIMELINE to HudPlacement(true, .5f, 1f, 1f)),
        )
        val timeline = engine().resolve(safe, HudOrientation.LANDSCAPE, profile, sizes(800f, 360f))
            .single { it.id == HudElementId.TIMELINE }

        assertEquals(safe.width, timeline.visualRect.width, .01f)
        assertTrue(timeline.visualRect.left >= safe.left - .01f)
        assertTrue(timeline.visualRect.right <= safe.right + .01f)
    }

    @Test
    fun `custom profiles round trip without changing placements`() {
        val profile = HudProfile(
            HudProfileMode.CUSTOM,
            1.23f,
            HudElementId.entries.associateWith { id ->
                HudPlacement(id.ordinal % 2 == 0, (id.ordinal + 1) / 30f, (id.ordinal + 2) / 31f, 1f)
            },
        )
        val config = PlayerHudConfig(portrait = profile, landscape = PlayerHudDefaults.config().landscape)
        val decoded = HudConfigJson.decode(HudConfigJson.encode(config))

        assertEquals(config, decoded)
    }

    private fun defaultProfile() = PlayerHudDefaults.config().landscape

    private fun engine(rtl: Boolean = false, density: Float = 1f) = HudLayoutEngine(density = density, rtl = rtl)

    private fun sizes(width: Float, height: Float, density: Float = 1f): Map<HudElementId, HudSize> =
        HudElementRegistry.all.associate { spec ->
            spec.id to if (spec.id == HudElementId.TIMELINE) {
                HudSize(width, 48f * density)
            } else {
                spec.visualSize(height < 260f / density).let { HudSize(it.width * density, it.height * density) }
            }
        }
}
