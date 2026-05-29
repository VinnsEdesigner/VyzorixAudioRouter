package com.vyzorix.audiorouter.common.audio

import android.content.ContentResolver
import android.net.Uri

/**
 * Builds `content://`-style URIs for raw audio resources.
 *
 * Per `doc/VyzorixAudioRouter_RepoTree.md` §core/common/audio/, the
 * daemon's service modules MUST NOT reference `R.raw.*` directly because
 * that pulls the app module's resource graph into Layer 3+ — instead,
 * services request the raw resource by URI through this helper, which
 * lives in Layer 0 and knows nothing about the calling app's resource
 * IDs.
 *
 * The URI scheme is `android.resource://<authority>/<resource_id>`, which
 * Android's `MediaPlayer` / `AssetFileDescriptor` machinery accepts
 * transparently. The caller provides the resource ID (sourced from
 * `R.raw.silent_anchor` in the app module).
 */
public object RawResourceUriHelper {

    /**
     * Returns a `content://` URI for [resourceId] under the supplied
     * package authority (typically the application's package name).
     *
     * Example:
     * ```kotlin
     * val uri = RawResourceUriHelper.forResource(
     *     packageName = context.packageName,
     *     resourceId = R.raw.silent_anchor,
     * )
     * mediaPlayer.setDataSource(context, uri)
     * ```
     */
    public fun forResource(packageName: String, resourceId: Int): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/$resourceId")
}
