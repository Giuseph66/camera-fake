package br.com.neurelix.vcam.engine.sources

import br.com.neurelix.vcam.core.model.PatternType
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.engine.FrameTarget
import br.com.neurelix.vcam.engine.SourceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Padrões de diagnóstico gerados 100% na GPU (barras, gradiente, quadros numerados com código binário, ruído). */
class TestPatternSource(val pattern: PatternType) : FrameSource {
    override val label: String get() = "Diagnóstico: ${pattern.label}"
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state

    override suspend fun start(target: FrameTarget) {
        target.submitProcedural(pattern)
        _state.value = SourceState.Running
    }

    override suspend fun stop() { _state.value = SourceState.Idle }
}
