package br.com.neurelix.vcam.core

import br.com.neurelix.vcam.core.diagnostics.DiagnosticsExporter
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.core.model.SourceKind
import br.com.neurelix.vcam.core.model.SourceSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun neutralFilterIsNeutral() {
        assertTrue(FilterParams().isNeutral)
        assertFalse(FilterParams(brightness = 0.1f).isNeutral)
        assertTrue(FilterParams(enabled = false, brightness = 1f).isNeutral)
    }

    @Test fun sanitizeClampsRanges() {
        val s = FilterParams(brightness = 9f, contrast = -3f, gamma = 100f, denoise = 5f, exposureEv = -9f).sanitized()
        assertEquals(1f, s.brightness, 0f); assertEquals(0f, s.contrast, 0f)
        assertEquals(2.5f, s.gamma, 0f); assertEquals(1f, s.denoise, 0f); assertEquals(-2f, s.exposureEv, 0f)
    }

    @Test fun powerSavingCapsFps() {
        assertEquals(15, OutputSettings(fps = 60, powerSaving = true).effectiveFps)
        assertEquals(30, OutputSettings(fps = 30).effectiveFps)
        assertEquals(10, OutputSettings(fps = 10, powerSaving = true).effectiveFps)
    }

    @Test fun serializationRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val spec = SourceSpec(SourceKind.IMAGE, "/data/x.jpg", "x.jpg")
        assertEquals(spec, json.decodeFromString(SourceSpec.serializer(), json.encodeToString(SourceSpec.serializer(), spec)))
        val o = OutputSettings(width = 720, height = 1280, mirrorHorizontal = true)
        assertEquals(o, json.decodeFromString(OutputSettings.serializer(), json.encodeToString(OutputSettings.serializer(), o)))
    }

    @Test fun exporterRedactsPrivateData() {
        val raw = "path=/data/user/0/br.com.neurelix.virtualcamera/files/sources/a.jpg uri=content://media/external/images/1 mail=jose@gmail.com /storage/emulated/0/DCIM"
        val s = DiagnosticsExporter.sanitize(raw)
        assertFalse(s.contains("jose@gmail.com")); assertFalse(s.contains("content://media"))
        assertFalse(s.contains("/data/user/0/br.com")); assertFalse(s.contains("/storage/emulated/0"))
    }
}
