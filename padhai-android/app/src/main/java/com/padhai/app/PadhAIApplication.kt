package com.padhai.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.padhai.app.utils.ThemeManager

class PadhAIApplication : Application() {

    companion object {
        private const val TAG = "PadhAIApplication"
    }

    override fun onCreate() {
        super.onCreate()

        ThemeManager.initializeTheme(this)

        val existingApps = FirebaseApp.getApps(this)

        if (existingApps.isNotEmpty()) {
            Log.d(TAG, "Firebase already initialized")
            Log.d(TAG, "Firebase project: ${existingApps[0].options.projectId}")
            return
        }

        val app = FirebaseApp.initializeApp(this)

        if (app != null) {
            Log.d(TAG, "Firebase initialized successfully")
            Log.d(TAG, "Firebase project: ${app.options.projectId}")
        } else {
            Log.e(TAG, "Firebase initialization failed")
        }
    }
}