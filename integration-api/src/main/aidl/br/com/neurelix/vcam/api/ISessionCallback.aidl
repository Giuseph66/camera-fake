package br.com.neurelix.vcam.api;

oneway interface ISessionCallback {
    /** engineState = ordinal de EngineState. */
    void onSourceStateChanged(int engineState, String sourceLabel);
    /** O serviço encerrou a sessão (usuário desligou a integração, fonte parada...). */
    void onSessionClosed(String reason);
}
