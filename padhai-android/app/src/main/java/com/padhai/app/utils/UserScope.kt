package com.padhai.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

object UserScope {
    fun uid(): String =
        FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

    fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences("${name}_${uid()}", Context.MODE_PRIVATE)
}