package br.com.neurelix.vcam.integration

import br.com.neurelix.vcam.engine.FrameSource

/** Estado de disponibilidade de um backend, sempre acompanhado de evidências verificáveis. */
sealed interface IntegrationStatus {
    val summary: String
    val evidence: List<String>

    data class Available(override val summary: String, override val evidence: List<String> = emptyList()) : IntegrationStatus
    data class Active(override val summary: String, override val evidence: List<String> = emptyList()) : IntegrationStatus
    data class Unavailable(override val summary: String, override val evidence: List<String> = emptyList()) : IntegrationStatus
}

sealed interface IntegrationResult {
    data class Success(val message: String) : IntegrationResult
    data class Failure(val message: String, val evidence: List<String> = emptyList()) : IntegrationResult
}

/**
 * Abstração independente de UI e motor. Cada backend diz honestamente o que consegue fazer
 * neste aparelho; nenhum backend relata sucesso sem que o mecanismo correspondente exista.
 */
interface CameraIntegrationBackend {
    val id: String
    val title: String
    /** O que este backend entrega e a quem. Texto exibido na UI. */
    val scope: String

    suspend fun checkAvailability(): IntegrationStatus
    suspend fun start(source: FrameSource): IntegrationResult
    suspend fun stop()
}
