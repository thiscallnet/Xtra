package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * Ignores every touch while the phone is in a pocket (proximity covered and the light sensor dark).
 *
 * The lock is applied at the window level for all activities and dialog fragments, so no screen needs
 * to know about it. It engages shortly after the sensors agree and releases the moment the proximity
 * sensor reports far again.
 */
object PocketTouchGuard {

    private const val ENGAGE_DELAY_MS = 300L
    private const val NEAR_DISTANCE_CM = 5f
    /** Above this the sensor is not in a pocket, even if proximity reports near. */
    private const val POCKET_MAX_LUX = 100f
    /** Keeps the sensors (and a held lock) alive across an activity recreate, where the started count briefly hits zero. */
    private const val STOP_GRACE_MS = 1000L

    @Volatile
    var isLocked = false
        private set

    private lateinit var app: Application
    private var installed = false
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var startedActivities = 0
    private var listening = false
    private var near = false
    private var nearKnown = false
    private var sawFar = false
    private var lux: Float? = null
    private val handler = Handler(Looper.getMainLooper())
    private var engagePending = false
    private val refreshRunnable = Runnable { refresh() }
    private val engage = Runnable {
        engagePending = false
        isLocked = true
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A null key means the whole store was cleared (settings restore), so re-read the value.
        if (key == null || key == C.POCKET_TOUCH_GUARD) refresh()
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_PROXIMITY -> {
                    val range = event.sensor.maximumRange
                    near = event.values[0] < minOf(range, NEAR_DISTANCE_CM)
                    nearKnown = true
                    if (!near) sawFar = true
                }
                Sensor.TYPE_LIGHT -> lux = event.values[0]
            }
            evaluate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun isSupported(context: Context): Boolean =
        (context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) return
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        application.rawPrefs().registerOnSharedPreferenceChangeListener(preferenceListener)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager
                    ?.registerFragmentLifecycleCallbacks(dialogCallbacks, true)
            }

            override fun onActivityStarted(activity: Activity) {
                guard(activity.window)
                startedActivities++
                handler.removeCallbacks(refreshRunnable)
                refresh()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) handler.postDelayed(refreshRunnable, STOP_GRACE_MS) else refresh()
            }

            // Re-check in case something replaced the window callback after start.
            override fun onActivityResumed(activity: Activity) = guard(activity.window)
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private val dialogCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) f.dialog?.window?.let(::guard)
        }
    }

    private fun guard(window: Window) {
        val current = window.callback ?: return
        if (current !is GuardCallback) window.callback = GuardCallback(current)
    }

    private fun refresh() {
        val wanted = startedActivities > 0 && app.rawPrefs().getBoolean(C.POCKET_TOUCH_GUARD, false)
        if (wanted == listening) return
        val manager = sensorManager ?: return
        if (wanted) {
            nearKnown = false
            sawFar = false
            lux = null
            manager.registerListener(sensorListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            lightSensor?.let { manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else {
            manager.unregisterListener(sensorListener)
            handler.removeCallbacks(engage)
            engagePending = false
            isLocked = false
        }
        listening = wanted
    }

    /**
     * Near plus a dark light sensor means pocket. Without a light reading, near only counts once the
     * sensor has been seen far, so a sensor stuck on near cannot lock the app on its own.
     */
    private fun evaluate() {
        val darkOrUnknown = lux?.let { it <= POCKET_MAX_LUX }
        val pocketed = nearKnown && near && (darkOrUnknown ?: sawFar)
        if (pocketed) {
            // Keep the pending timer on later sensor noise so it cannot be pushed back indefinitely.
            if (!isLocked && !engagePending) {
                engagePending = true
                handler.postDelayed(engage, ENGAGE_DELAY_MS)
            }
        } else {
            handler.removeCallbacks(engage)
            engagePending = false
            isLocked = false
        }
    }

    private class GuardCallback(private val base: Window.Callback) : Window.Callback by base {
        private var swallowing = false
        private var delivering = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                swallowing = isLocked
                delivering = !swallowing
            } else if (!swallowing && isLocked) {
                swallowing = true
                if (delivering) {
                    // A gesture started before the lock engaged: cancel it so no control sees a stray UP.
                    delivering = false
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        base.dispatchTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                }
            }
            return swallowing || base.dispatchTouchEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            isLocked || base.dispatchGenericMotionEvent(event)

        override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
            isLocked || base.dispatchTrackballEvent(event)

        // Window.Callback default methods are not forwarded by Kotlin delegation.
        override fun onPointerCaptureChanged(hasCapture: Boolean) = base.onPointerCaptureChanged(hasCapture)

        override fun onProvideKeyboardShortcuts(
            data: MutableList<android.view.KeyboardShortcutGroup>?,
            menu: Menu?,
            deviceId: Int,
        ) = base.onProvideKeyboardShortcuts(data, menu, deviceId)

        override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = base.onSearchRequested(searchEvent)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
            base.onWindowStartingActionMode(callback, type)
    }
}
