package br.com.neurelix.vcam.integration.local

import br.com.neurelix.vcam.EngineController
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.integration.CameraIntegrationBackend
import br.com.neurelix.vcam.integration.IntegrationResult
import br.com.neurelix.vcam.integration.IntegrationStatus

/** Operação local: processa e exibe dentro do próprio aplicativo (preview e captura local). Sem efeito em outros apps. */
class LocalBackend(private val controller: EngineController) : CameraIntegrationBackend {
    override val id = "local"
    override val title = "Processamento local"
    override val scope = "Preview e captura dentro do NEURELIX. Não é visível a outros aplicativos."

    override suspend fun checkAvailability(): IntegrationStatus =
        if (controller.running.value) IntegrationStatus.Active("Processamento local ativo")
        else IntegrationStatus.Available("Pronto para iniciar")

    override suspend fun start(source: FrameSource): IntegrationResult = try {
        controller.engine.setSource(source)
        IntegrationResult.Success("Fonte '${source.label}' ativa localmente")
    } catch (t: Throwable) { IntegrationResult.Failure(t.message ?: "falha") }

    override suspend fun stop() { controller.stop() }
}
