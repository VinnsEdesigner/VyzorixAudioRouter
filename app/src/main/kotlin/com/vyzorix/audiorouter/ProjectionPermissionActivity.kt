// ProjectionPermissionActivity — translucent one-shot trampoline that
// requests a MediaProjection token.
//
// Per doc/MEDIA_PROJECTION_FLOW.md §Phase 1:
//   1. PersistentAudioService (or BootstrapCoordinator) launches this
//      activity when it needs a fresh projection token.
//   2. onCreate fires startActivityForResult against
//      MediaProjectionManager.createScreenCaptureIntent().
//   3. Android shows the consent dialog. AccessibilityGestureQueue
//      auto-clicks "Start Now" in <100ms (Layer 4 accessibility surface).
//   4. The result returns to onActivityResult. We forward the
//      (resultCode, data) pair to PersistentAudioService via a private
//      action broadcast + Intent extras.
//   5. Activity calls finish() immediately.
//
// The activity is themed translucent in the manifest so the user never
// sees a "blank" trampoline window.

package com.vyzorix.audiorouter

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.vyzorix.audiorouter.services.capture.ProjectionPermissionContract

/** One-shot MediaProjection consent trampoline. */
public class ProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val triggerOrigin =
            intent?.getStringExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN)
                ?: ProjectionPermissionContract.ORIGIN_UNKNOWN

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "media_projection_service_unavailable")
            forwardResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                triggerOrigin = triggerOrigin,
                error = "media_projection_service_unavailable",
            )
            finish()
            return
        }
        val captureIntent: Intent = try {
            projectionManager.createScreenCaptureIntent()
        } catch (t: Throwable) {
            Log.e(TAG, "createScreenCaptureIntent threw: ${t.javaClass.simpleName} ${t.message}")
            forwardResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                triggerOrigin = triggerOrigin,
                error = "create_screen_capture_intent_threw",
            )
            finish()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, ProjectionPermissionContract.REQUEST_CODE_PROJECTION)
        } catch (t: Throwable) {
            Log.e(TAG, "startActivityForResult threw: ${t.javaClass.simpleName} ${t.message}")
            forwardResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                triggerOrigin = triggerOrigin,
                error = "start_activity_for_result_threw",
            )
            finish()
        }
    }

    @Deprecated("Layer 5 will migrate to Activity Result API once a Compose UI exists.")
    @Suppress("OVERRIDE_DEPRECATION")
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ProjectionPermissionContract.REQUEST_CODE_PROJECTION) {
            finish()
            return
        }
        val triggerOrigin =
            intent?.getStringExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN)
                ?: ProjectionPermissionContract.ORIGIN_UNKNOWN
        forwardResult(resultCode = resultCode, data = data, triggerOrigin = triggerOrigin)
        finish()
    }

    private fun forwardResult(
        resultCode: Int,
        data: Intent?,
        triggerOrigin: String,
        error: String? = null,
    ) {
        val broadcast = Intent(ProjectionPermissionContract.ACTION_PROJECTION_RESULT).apply {
            setPackage(packageName)
            putExtra(ProjectionPermissionContract.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN, triggerOrigin)
            if (data != null) {
                putExtra(ProjectionPermissionContract.EXTRA_RESULT_DATA, data)
            }
            if (error != null) {
                putExtra(ProjectionPermissionContract.EXTRA_RESULT_ERROR, error)
            }
        }
        sendBroadcast(broadcast)
        Log.i(TAG, "result_forwarded resultCode=$resultCode origin=$triggerOrigin error=$error")
    }

    public companion object {
        public const val TAG: String = "ProjectionPermissionActivity"
    }
}
