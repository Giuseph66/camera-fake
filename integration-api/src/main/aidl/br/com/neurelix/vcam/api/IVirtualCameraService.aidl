package br.com.neurelix.vcam.api;

import br.com.neurelix.vcam.api.IVirtualCameraSession;
import br.com.neurelix.vcam.api.ISessionCallback;
import br.com.neurelix.vcam.api.ServiceStatus;
import br.com.neurelix.vcam.api.SessionRequest;

interface IVirtualCameraService {
    int getApiVersion();
    /** Estado do serviço (não exige autorização, não revela conteúdo). */
    ServiceStatus getStatus();
    /**
     * Abre sessão. Lança SecurityException se o app chamador não foi autorizado pelo usuário
     * na tela "Integração" do NEURELIX, ou IllegalStateException se a integração externa/fonte estiver inativa.
     */
    IVirtualCameraSession openSession(in SessionRequest request, ISessionCallback callback);
}
