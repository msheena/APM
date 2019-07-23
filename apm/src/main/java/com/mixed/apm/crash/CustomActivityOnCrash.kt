package com.mixed.apm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.text.SpannableStringBuilder
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.Thread.setDefaultUncaughtExceptionHandler
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.reflect.KClass

const val TAG = "CustomActivityOnCrash"
const val HANDLER_NAME = "com.mixed.apm.CustomActivityOnCrash"
const val DEFAULT_HANDLER_NAME="com.android.internal.os"
const val SHOW_ERROR_DETAILS = "CustomActivityOnCrash.SHOW_ERROR_DETAILS"
const val IMAGE_DRAWABLE_ID = "CustomActivityOnCrash.IMAGE_DRAWABLE_ID"
const val STACK_TRACE = "CustomActivityOnCrash.STACK_TRACE"
const val INTENT_ACTION_ERROR_ACTIVITY = "com.mixed.apm.CustomActivityOnCrash.INTENT_ACTION_ERROR_ACTIVITY"
const val MAX_STACK_TRACE_SIZE = 131071
const val RESTART_ACTIVITY_CLASS="CustomActivityOnCrash.RESTART_ACTIVITY_CLASS"
const val INTENT_ACTION_RESTART_ACTIVITY = "com.mixed.apm.CustomActivityOnCrash.RESTART"


class CustomActivityOnCrash : AppCompatActivity() {

    companion object {
        private var errorActivityClass: Class<out Activity>?=null
        private var restartActivityClass: Class<out Activity>? = null

        private var isInBackground = false
        private var launchErrorActivityWhenInBackground = true
        private var showErrorDetails = true
        private var enableAppRestart = true

        lateinit var lastActivityCreated :WeakReference<Activity>

        fun install(context: Context) {

            try {
                if (context == null) {
                    Log.e(TAG, "context is null, install failed")
                    return
                }
                val handler = Thread.getDefaultUncaughtExceptionHandler()
                if (handler != null && handler.javaClass.name.startsWith(HANDLER_NAME)) {
                    Log.e(TAG, "CustomActivityOnCrash already installed!!!")

                } else {
                    if (handler != null && !handler.javaClass.name.startsWith(DEFAULT_HANDLER_NAME)) {
                        Log.e(
                            TAG,
                            "You already have an UncaughtExceptionHandler. CustomActivityOnCrash must be install first!"
                        )
                    }
                    val application = context.applicationContext as Application
                    setDefaultUncaughtExceptionHandler { thread, throwable ->
                        Log.e(
                            TAG,
                            "App has crashed, executing CustomActivityOnCrash's UncaughtExceptionHandler", throwable
                        )
                        if (errorActivityClass == null) {
                            errorActivityClass = guessErrorActivityClass(application)
                        }

                        if (isStackTraceLikelyConflictive(throwable, errorActivityClass!!)) {
                            Log.e(
                                TAG,
                                "Your application class or your error activity have crashed, the custom activity will not be launched!"
                            )
                        } else {
                            if (launchErrorActivityWhenInBackground || !isInBackground) {
                                val intent = Intent(application, errorActivityClass)
                                val sw = StringWriter()
                                val pw = PrintWriter(sw)
                                throwable.printStackTrace(pw)
                                var stackTraceString = sw.toString()

                                //Reduce data to 128KB so we don't get a TransactionTooLargeException when sending the intent.
                                //The limit is 1MB on Android but some devices seem to have it lower.
                                //See: http://developer.android.com/reference/android/os/TransactionTooLargeException.html
                                //And: http://stackoverflow.com/questions/11451393/what-to-do-on-transactiontoolargeexception#comment46697371_12809171
                                if (stackTraceString.length > MAX_STACK_TRACE_SIZE) {
                                    val disclaimer = " [stack trace too large]"
                                    stackTraceString = stackTraceString.substring(0, MAX_STACK_TRACE_SIZE - disclaimer.length) + disclaimer
                                }

                                if (enableAppRestart && restartActivityClass == null) {
                                    restartActivityClass = guessRestartActivityClass(application)
                                } else if (!enableAppRestart) {
                                    restartActivityClass = null
                                }

                                intent.putExtra(STACK_TRACE, stackTraceString)
                                intent.putExtra(RESTART_ACTIVITY_CLASS, restartActivityClass)
                                intent.putExtra(SHOW_ERROR_DETAILS, showErrorDetails)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                application.startActivity(intent)
                            }
                        }
                        val lastActivity = lastActivityCreated.get()
                        if (lastActivity != null) {
                            lastActivity!!.finish()
                            lastActivityCreated.clear()
                        }
                        killCurrentProcess()
                    }
                    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        internal var currentStartedActivities = 0


                        override fun onActivityCreated(p0: Activity, p1: Bundle?) {
                            if (p0.javaClass != errorActivityClass) {
                                // Copied from ACRA:
                                // Ignore activityClass because we want the last application Activity that was started so that we can
                                // explicitly kill it off.
                                lastActivityCreated = WeakReference(p0)
                            }
                        }

                        override fun onActivityStarted(p0: Activity) {
                            currentStartedActivities++
                            isInBackground = currentStartedActivities == 0
                        }

                        override fun onActivityResumed(p0: Activity) {
                        }

                        override fun onActivityPaused(p0: Activity) {

                        }

                        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
                        }

                        override fun onActivityStopped(p0: Activity) {
                            currentStartedActivities--
                            isInBackground = currentStartedActivities == 0
                        }


                        override fun onActivityDestroyed(p0: Activity) {
                        }


                    })

                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unknown error occurred!")

            }

        }

        /**
         * INTERNAL method used to guess which error activity must be called when the app crashes.
         * It will first get activities from the AndroidManifest with intent filter <action
         * android:name="com.mixed.apm.CustomActivityOnCrash.Error" />,
         * if it cannot find them, then it will use the default error activity.
         */
        private fun guessErrorActivityClass(context: Context): Class<out Activity> {
            var resolvedActivityClass: Class<out Activity>?

            resolvedActivityClass = getErrorActivityClassWithIntentFilter(context)

            if (resolvedActivityClass == null) {
                resolvedActivityClass = DefaultErrorActivity::class.java
            }

            return resolvedActivityClass
        }

        fun getErrorActivityClassWithIntentFilter(context: Context): Class<out Activity>? {
            val resolveInfos = context.packageManager.queryIntentActivities(
                Intent().setAction(INTENT_ACTION_ERROR_ACTIVITY),
                PackageManager.GET_RESOLVED_FILTER
            )

            if (resolveInfos != null && resolveInfos.size > 0) {
                val resolveInfo = resolveInfos[0]
                try {
                    return Class.forName(resolveInfo.activityInfo.name) as Class<out Activity>
                } catch (e: ClassNotFoundException) {
                    Log.e(
                        TAG,
                        "Failed when resolving the error activity class via intent filter, stack trace follows!",
                        e
                    )
                }

            }
            return null
        }

        private fun isStackTraceLikelyConflictive(throwable: Throwable, activityClass: Class<out Activity>): Boolean {
            var tw :Throwable?= throwable

            do {
                val stackTrace = tw?.stackTrace
                stackTrace?.forEach {
                    if (it.className == "android.app.ActivityThread"
                        && it.methodName == "handleBindApplication"
                        || it.className == activityClass!!.name
                    ) {
                        return true
                    }
                }
            } while(tw?.cause.also { tw=it }!=null)
            return false


        }

        private fun guessRestartActivityClass(context: Context): Class<out Activity> ?{
            var resolvedActivityClass: Class<out Activity>?
            resolvedActivityClass =getRestartActivityClassWithIntentFilter(context)
            if (resolvedActivityClass == null) {
                resolvedActivityClass = getLauncherActivity(context)
            }
            return resolvedActivityClass
        }

        private fun getRestartActivityClassWithIntentFilter(context: Context): Class<out Activity>? {
            val resolveInfos = context.packageManager.queryIntentActivities(
                Intent().setAction(INTENT_ACTION_RESTART_ACTIVITY),
                PackageManager.GET_RESOLVED_FILTER
            )

            if (resolveInfos != null && resolveInfos.size > 0) {

                val resolveInfo = resolveInfos[0]
                try {
                    return Class.forName(resolveInfo.activityInfo.name) as Class<out Activity>
                } catch (e: ClassNotFoundException) {
                    Log.e(
                        TAG,
                        "Failed when resolving the restart activity class via intent filter, stack trace follows!", e
                    )
                }

            }

            return null
        }
        private fun getLauncherActivity(context: Context): Class<out Activity>? {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                try {
                    return Class.forName(intent.component!!.className) as Class<out Activity>
                } catch (e: ClassNotFoundException) {
                    Log.e(
                        TAG,
                        "Failed when resolving the restart activity class via getLaunchIntentForPackage, stack trace follows!",
                        e
                    )
                }

            }

            return null
        }

        fun isShowErrorDetailsFromIntent(intent: Intent): Boolean {
            return intent.getBooleanExtra(SHOW_ERROR_DETAILS, true)
        }

        fun getErrorActivityDrawableIdFromIntent(intent: Intent): Int {
            return intent.getIntExtra(IMAGE_DRAWABLE_ID, R.drawable.customactivityoncrash_error_image)
        }


        @JvmStatic
        fun getErrorDetailsFromIntent(context: Context, intent: Intent): String {
            val currentDate = Date()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val buildDateAsString = getBuildDateAsString(context, dateFormat)
            val versionName = getVersionName(context)
            var errorDetails = ""

            errorDetails += "Build version: $versionName \n"
            errorDetails += "Build date: $buildDateAsString \n"
            errorDetails += "Current date: " + dateFormat.format(currentDate) + " \n"
            errorDetails += "Device: " + getDeviceModelName() + " \n\n"
            errorDetails += "Stack trace:  \n"
            errorDetails += getStackTraceFromIntent(intent)
            return errorDetails
        }

        fun getBuildDateAsString(context: Context, dateFormat: DateFormat): String {
            var buildDate: String
            try {
                val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
                val zf = ZipFile(ai.sourceDir)
                val ze = zf.getEntry("classes.dex")
                val time = ze.time
                buildDate = dateFormat.format(Date(time))
                zf.close()
            } catch (e: Exception) {
                buildDate = "Unknown"
            }

            return buildDate
        }

        fun getVersionName(context: Context): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName
            } catch (e: Exception) {
                "Unknown"
            }

        }

        private fun getDeviceModelName(): String {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            return if (model.startsWith(manufacturer)) {

                capitalize(model)
            } else {
                capitalize(manufacturer) + " " + model
            }
        }

        private fun capitalize(s: String?): String {
            if (s == null || s.isEmpty()) {
                return ""
            }
            val first = s[0]
            return if (Character.isUpperCase(first)) {
                s
            } else {
                Character.toUpperCase(first) + s.substring(1)
            }
        }

        fun getStackTraceFromIntent(intent: Intent): String {
            return intent.getStringExtra(STACK_TRACE)
        }

        @JvmStatic
        fun getRestartActivityFromIntent(intent: Intent): Class<out Activity>? {
            val serializedClass = intent.getSerializableExtra(RESTART_ACTIVITY_CLASS)

            return if (serializedClass != null && serializedClass is Class<*>) {
                serializedClass as Class<out Activity>
            } else {
                null
            }
        }
        fun killCurrentProcess() {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }

        fun closeApplication(activity: Activity) {
            activity.finish()
            killCurrentProcess()
        }

        fun restartAppWithIntent(activity: Activity, intent: Intent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.finish()
            activity.startActivity(intent)
            killCurrentProcess()
        }
    }


}
