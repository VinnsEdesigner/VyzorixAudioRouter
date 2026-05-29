package com.vyzorix.audiorouter.services.projection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.utils.NotificationChannelManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectionLaunchConditionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `evaluate produces either Ready or Blocked`() {
        NotificationChannelManager.ensureChannels(context)
        val conditions = ProjectionLaunchConditions(context = context)
        val outcome = conditions.evaluate()
        assertTrue(outcome is ProjectionLaunchCondition.Ready ||
            outcome is ProjectionLaunchCondition.Blocked)
    }

    @Test fun `snapshot reflects the last evaluation`() {
        NotificationChannelManager.ensureChannels(context)
        val conditions = ProjectionLaunchConditions(context = context)
        conditions.evaluate()
        val snap = conditions.snapshot()
        assertTrue(snap.lastResultLabel.isNotEmpty())
    }
}
