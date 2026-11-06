package br.com.neurelix.vcam.integration.consumers

import android.content.Context
import android.content.pm.PackageManager
import br.com.neurelix.vcam.core.model.AuthorizedConsumer
import java.security.MessageDigest

data class CallerInfo(val packageName: String, val certSha256: String, val label: String)

object ConsumerAuth {
    fun resolve(context: Context, uid: Int): CallerInfo? {
        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
        val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return null
        val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        val sha = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        val label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }.getOrDefault(pkg)
        return CallerInfo(pkg, sha, label)
    }

    fun isAuthorized(list: List<AuthorizedConsumer>, c: CallerInfo) =
        list.any { it.packageName == c.packageName && it.certSha256 == c.certSha256 }
}
