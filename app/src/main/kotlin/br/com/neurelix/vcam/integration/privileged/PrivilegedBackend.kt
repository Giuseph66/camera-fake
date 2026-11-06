package br.com.neurelix.vcam.integration.privileged

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.engine.FrameSource
import br.com.neurelix.vcam.integration.CameraIntegrationBackend
import br.com.neurelix.vcam.integration.IntegrationResult
import br.com.neurelix.vcam.integration.IntegrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Integração de sistema (câmera global registrada no CameraService/HAL).
 *
 * NÃO implementada neste aparelho: o Galaxy S20 FE está sem root, com bootloader travado, build `user`
 * e SELinux enforcing. Este backend NÃO simula nada: ele coleta evidências reais (somente leitura, sem executar `su`)
 * e responde Unavailable/Failure enquanto os pré-requisitos não existirem. O que seria necessário e os riscos
 * estão em system/README.md e docs/RECOVERY.md; nenhuma alteração de sistema é feita sem autorização explícita.
 */
class PrivilegedBackend(private val context: Context) : CameraIntegrationBackend {
    override val id = "privileged"
    override val title = "Câmera de sistema (privilegiada)"
    override val scope = "Registro de uma câmera virtual no CameraService/HAL para qualquer app. Exige root/ROM modificada."

    data class Probe(val rootBinary: String?, val isSystemApp: Boolean, val buildType: String, val tags: String,
                     val selinuxEnforcing: Boolean?, val bootloaderLocked: String?, val verifiedBoot: String?)

    suspend fun probe(): Probe = withContext(Dispatchers.IO) {
        val dirs = (System.getenv("PATH") ?: "/system/bin:/system/xbin").split(":") +
            listOf("/sbin", "/system/sbin", "/vendor/bin", "/su/bin", "/magisk/.core/bin", "/data/adb/magisk")
        val su = dirs.map { File(it, "su") }.firstOrNull { it.exists() }?.absolutePath
        val enforce = runCatching { File("/sys/fs/selinux/enforce").readText().trim() == "1" }.getOrNull()
        Probe(
            rootBinary = su,
            isSystemApp = (context.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            buildType = Build.TYPE, tags = Build.TAGS,
            selinuxEnforcing = enforce,
            bootloaderLocked = prop("ro.boot.flash.locked"),
            verifiedBoot = prop("ro.boot.verifiedbootstate"),
        )
    }

    private fun prop(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { null }.also { p.waitFor() }
    }.getOrNull()

    override suspend fun checkAvailability(): IntegrationStatus {
        val p = probe()
        val ev = listOf(
            "binário su: ${p.rootBinary ?: "não encontrado"}",
            "app é de sistema/privilegiado: ${if (p.isSystemApp) "sim" else "não (APK comum, uid de app)"}",
            "build: type=${p.buildType} tags=${p.tags}",
            "SELinux enforcing: ${p.selinuxEnforcing ?: "ilegível para apps"}",
            "bootloader travado (ro.boot.flash.locked): ${p.bootloaderLocked ?: "?"}",
            "verified boot: ${p.verifiedBoot ?: "?"}",
        )
        AppLog.i("privileged", "probe", ev.joinToString("; "))
        val blockers = buildList {
            if (p.rootBinary == null) add("sem root")
            if (!p.isSystemApp) add("app sem privilégio de sistema")
            if (p.bootloaderLocked == "1") add("bootloader travado")
            if (p.selinuxEnforcing == true) add("SELinux enforcing")
        }
        return if (p.rootBinary != null && p.isSystemApp)
            IntegrationStatus.Unavailable("Pré-requisitos parciais presentes, mas o componente de HAL/CameraService ainda não está instalado (não implementado).", ev)
        else IntegrationStatus.Unavailable("Indisponível: ${blockers.joinToString(", ")}", ev)
    }

    override suspend fun start(source: FrameSource): IntegrationResult {
        val st = checkAvailability()
        return IntegrationResult.Failure(
            "Câmera virtual de sistema não pode ser registrada: ${st.summary}. Veja system/README.md para a menor intervenção necessária (requer sua autorização).",
            st.evidence,
        )
    }

    override suspend fun stop() { /* nada ativo */ }
}
