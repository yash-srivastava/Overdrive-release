package com.overdrive.app.ui.fragment

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class ProjectionSealion7LayoutTest {
    @Test
    fun uncheckedFullHdmiSwitchReceivesTouchAlongsideFullWidthClusterBarsCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val check = FutureTask { verifyLayout(instrumentation.targetContext) }
        instrumentation.runOnMainSync(check)
        try {
            check.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun verifyLayout(target: android.content.Context) {
        val widthDp = 1280
        val heightDp = 720
        val landscape = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = widthDp
            screenHeightDp = heightDp
        })
        val context = ContextThemeWrapper(landscape, R.style.Theme_Overdrive_M3)
        val root = LayoutInflater.from(context).inflate(R.layout.fragment_projection, null) as ViewGroup
        val row = root.findViewById<View>(R.id.projectionSealion7HdmiRow)
        val toggle = root.findViewById<MaterialSwitch>(R.id.projectionSealion7HdmiSwitch)

        // The public build stubs out the optional Cluster Bars card attached after ADAS settings.
        val adasCard = generateSequence(root.findViewById<View>(R.id.projectionAdasHideSwitch)) {
            it.parent as? View
        }.filterIsInstance<MaterialCardView>().first()
        val settings = adasCard.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, settings.orientation)
        val clusterBars = MaterialCardView(context)
        settings.addView(
            clusterBars, settings.indexOfChild(adasCard) + 1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
            )
        )

        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }

        assertEquals(View.GONE, row.visibility)
        layout()
        val hiddenSettingsTop = settings.top
        assertTrue(clusterBars.width > 0)

        row.visibility = View.VISIBLE
        layout()
        assertFalse(toggle.isChecked)
        assertTrue("The Full HDMI switch must have usable bounds before opt-in",
            toggle.width > 0 && toggle.height > 0)
        val visibleBounds = Rect()
        assertTrue(toggle.getGlobalVisibleRect(visibleBounds))

        var receivedTouch = false
        toggle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) receivedTouch = true
            false
        }
        fun touchSwitchPosition() {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                val event = MotionEvent.obtain(now, now, action,
                    visibleBounds.exactCenterX(), visibleBounds.exactCenterY(), 0)
                try {
                    root.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        touchSwitchPosition()
        assertTrue("The visible switch must receive a touch at its center", receivedTouch)
        toggle.performClick()
        assertTrue(toggle.isChecked)

        row.visibility = View.GONE
        layout()
        assertEquals(hiddenSettingsTop, settings.top)
        receivedTouch = false
        touchSwitchPosition()
        assertFalse("The hidden switch must not receive touches", receivedTouch)
    }
}
