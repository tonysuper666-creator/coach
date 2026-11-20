package com.coach.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** What the installer will see in a package: its application id and the SHA-256 of every signing certificate. */
internal data class ApkIdentity(val packageName: String, val certificates: Set<String>)

private fun certificateDigests(signatures: Array<Signature>?): Set<String> =
    signatures.orEmpty().mapTo(LinkedHashSet()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }

internal fun installedIdentity(context: Context): ApkIdentity? = runCatching {
    val manager = context.packageManager
    val info = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    ApkIdentity(info.packageName, certificateDigests(info.signingInfo?.apkContentsSigners))
}.getOrNull()

internal fun archiveIdentity(context: Context, apk: File): ApkIdentity? = runCatching {
    val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: return null
    ApkIdentity(info.packageName, certificateDigests(info.signingInfo?.apkContentsSigners))
}.getOrNull()

/**
 * The downloaded package must be the same app signed by the same key; anything else is refused before the
 * installer opens, because a different key would either fail the upgrade or replace the learner's data.
 */
internal fun sameInstallIdentity(installed: ApkIdentity?, candidate: ApkIdentity?): Boolean =
    installed != null && candidate != null && installed.packageName == candidate.packageName &&
        installed.certificates.isNotEmpty() && installed.certificates == candidate.certificates

/** Hands the verified package to the system installer; the installer keeps app data on an in-place upgrade. */
internal fun updateInstallIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** System page where the learner allows this app to hand packages to the installer. */
internal fun installPermissionIntent(packageName: String): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
