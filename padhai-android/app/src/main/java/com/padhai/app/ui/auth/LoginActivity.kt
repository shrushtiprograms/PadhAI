package com.padhai.app.ui.auth

import com.padhai.app.BuildConfig
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.padhai.app.databinding.ActivityLoginBinding
import com.padhai.app.ui.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.text.InputType
import android.util.Patterns
import com.padhai.app.R
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setGoogleLoading(true)
                lifecycleScope.launch {
                    try {
                        val account = GoogleSignIn
                            .getSignedInAccountFromIntent(result.data)
                            .getResult(ApiException::class.java)

                        val credential = GoogleAuthProvider.getCredential(
                            account.idToken,
                            null
                        )

                        val authResult =
                            auth.signInWithCredential(credential).await()

                        val user = authResult.user
                            ?: throw IllegalStateException("Google user not found")

                        FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(user.uid)
                            .set(
                                mapOf(
                                    "uid" to user.uid,
                                    "name" to (user.displayName ?: ""),
                                    "email" to (user.email ?: ""),
                                    "createdAt" to FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            .await()

                        openMainActivity()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@LoginActivity,
                            "Google sign-in failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        setGoogleLoading(false)
                    }
                }
            } else {
                setGoogleLoading(false)
            }
        }

    private fun setGoogleLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnGoogle.isEnabled = !loading
        binding.btnLogin.isEnabled = !loading
        binding.tvSignup.isEnabled = !loading
        binding.tvForgotPassword.isEnabled = !loading
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    private suspend fun postAuthRequest(
        path: String,
        body: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                JSONObject()
            }

            if (status !in 200..299) {
                throw IllegalStateException(
                    response.optString("message", "Request failed ($status)")
                )
            }

            response
        } finally {
            connection.disconnect()
        }
    }

    private fun showPasswordResetDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val etResetEmail = view.findViewById<EditText>(R.id.etResetEmail)
        val tvResetEmailError = view.findViewById<TextView>(R.id.tvResetEmailError)
        val btnSendOtp = view.findViewById<Button>(R.id.btnSendOtp)

        // Prefill the dialog if the user already entered an email on the login screen.
        etResetEmail.setText(binding.etEmail.text?.toString()?.trim().orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        btnSendOtp.setOnClickListener {
            val email = etResetEmail.text?.toString()?.trim().orEmpty()
            if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                tvResetEmailError.text = "Enter a valid email address"
                tvResetEmailError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnSendOtp.isEnabled = false
            tvResetEmailError.visibility = View.GONE
            lifecycleScope.launch {
                try {
                    postAuthRequest(
                        "/api/auth/password-reset/send-otp",
                        JSONObject().put("email", email)
                    )

                    dialog.dismiss()
                    Toast.makeText(
                        this@LoginActivity,
                        "OTP sent to your email",
                        Toast.LENGTH_SHORT
                    ).show()
                    showOtpVerificationDialog(email)
                } catch (e: Exception) {
                    btnSendOtp.isEnabled = true
                    tvResetEmailError.text = e.message ?: "Failed to send OTP"
                    tvResetEmailError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showOtpVerificationDialog(email: String) {
        val view = layoutInflater.inflate(R.layout.dialog_verify_otp, null)

        val otpFields = listOf(
            view.findViewById<EditText>(R.id.etOtp1),
            view.findViewById<EditText>(R.id.etOtp2),
            view.findViewById<EditText>(R.id.etOtp3),
            view.findViewById<EditText>(R.id.etOtp4),
            view.findViewById<EditText>(R.id.etOtp5),
            view.findViewById<EditText>(R.id.etOtp6)
        )
        val tvOtpError = view.findViewById<TextView>(R.id.tvOtpError)
        val btnVerifyOtp = view.findViewById<Button>(R.id.btnVerifyOtp)
        val tvResendOtp = view.findViewById<TextView>(R.id.tvResendOtp)
        var distributingOtp = false

        otpFields.forEachIndexed { index, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(editable: Editable?) {
                    if (distributingOtp) return
                    val value = editable?.toString().orEmpty()
                    if (value.length > 1) {
                        val digits = value.filter { it.isDigit() }.take(6)
                        distributingOtp = true
                        otpFields.forEachIndexed { i, otpField ->
                            otpField.setText(digits.getOrNull(i)?.toString().orEmpty())
                        }
                        distributingOtp = false
                        otpFields.getOrNull((digits.length - 1).coerceAtLeast(0))?.requestFocus()
                    } else if (value.isNotEmpty() && index < otpFields.lastIndex) {
                        otpFields[index + 1].requestFocus()
                    }
                    if (value.isNotEmpty()) tvOtpError.visibility = View.GONE
                }
            })
            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    field.text.isNullOrEmpty() && index > 0
                ) {
                    otpFields[index - 1].requestFocus()
                    otpFields[index - 1].setSelection(otpFields[index - 1].text?.length ?: 0)
                    true
                } else false
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        btnVerifyOtp.setOnClickListener {
            val otp = otpFields.joinToString("") { it.text?.toString().orEmpty() }.trim()

            if (!otp.matches(Regex("^\\d{6}$"))) {
                tvOtpError.text = "Enter the valid 6-digit OTP"
                tvOtpError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // The current backend verifies the OTP together with the new password.
            // Here we validate its format and continue to the password dialog.
            dialog.dismiss()
            showResetPasswordDialog(email, otp)
        }

        tvResendOtp.setOnClickListener {
            lifecycleScope.launch {
                try {
                    postAuthRequest(
                        "/api/auth/password-reset/send-otp",
                        JSONObject().put("email", email)
                    )

                    otpFields.forEach { it.text?.clear() }
                    otpFields.first().requestFocus()
                    tvOtpError.visibility = View.GONE

                    Toast.makeText(
                        this@LoginActivity,
                        "A new OTP has been sent",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (e: Exception) {
                    Toast.makeText(
                        this@LoginActivity,
                        e.message ?: "Could not resend OTP",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showResetPasswordDialog(email: String, otp: String) {
        val view = layoutInflater.inflate(R.layout.dialog_reset_password, null)

        val etNewPassword = view.findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = view.findViewById<EditText>(R.id.etConfirmPassword)
        val tvPasswordError = view.findViewById<TextView>(R.id.tvPasswordError)
        val btnResetPassword = view.findViewById<Button>(R.id.btnResetPassword)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        btnResetPassword.setOnClickListener {
            val newPassword = etNewPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()

            when {
                newPassword.length < 8 -> {
                    tvPasswordError.text =
                        "Password must contain at least 8 characters"
                    tvPasswordError.visibility = View.VISIBLE
                }

                newPassword != confirmPassword -> {
                    tvPasswordError.text = "Passwords do not match"
                    tvPasswordError.visibility = View.VISIBLE
                }

                else -> {
                    tvPasswordError.visibility = View.GONE
                    btnResetPassword.isEnabled = false

                    lifecycleScope.launch {
                        try {
                            postAuthRequest(
                                "/api/auth/password-reset/verify-otp",
                                JSONObject()
                                    .put("email", email)
                                    .put("otp", otp)
                                    .put("newPassword", newPassword)
                            )

                            dialog.dismiss()
                            binding.etPassword.text?.clear()

                            Toast.makeText(
                                this@LoginActivity,
                                "Password reset successful. Please log in.",
                                Toast.LENGTH_LONG
                            ).show()

                        } catch (e: Exception) {
                            btnResetPassword.isEnabled = true
                            tvPasswordError.text =
                                e.message ?: "Password reset failed. Check your OTP and try again."
                            tvPasswordError.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun startGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(
            GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        val client = GoogleSignIn.getClient(this, options)
        setGoogleLoading(true)
        // Clear the previously selected Google account so the account chooser appears again.
        client.signOut().addOnCompleteListener(this) {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    
    companion object {
        private const val TAG = "LoginActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            auth = FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed", e)
            Toast.makeText(
                this,
                "Firebase initialization failed. Please reinstall and sync project once.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setupListeners()
    }
    
    private fun setupListeners() {
        binding.btnGoogle.setOnClickListener {
            startGoogleSignIn()
        }
        binding.tvForgotPassword.setOnClickListener {
            showPasswordResetDialog()
        }
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            
            if (validateInput(email, password)) {
                loginUser(email, password)
            }
        }
        
        binding.tvSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }
    
    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            binding.etEmail.error = "Email is required"
            return false
        }
        
        if (password.isEmpty()) {
            binding.etPassword.error = "Password is required"
            return false
        }
        
        if (password.length < 6) {
            binding.etPassword.error = "Password must be at least 6 characters"
            return false
        }
        
        return true
    }
    
    private fun loginUser(email: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        
        lifecycleScope.launch {
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                Toast.makeText(this@LoginActivity, "Login successful!", Toast.LENGTH_SHORT).show()
                
                // Navigate to main activity
                val intent = Intent(this@LoginActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                
            } catch (e: Exception) {
                Log.e(TAG, "Login failed: ${e.message}", e)
                Toast.makeText(
                    this@LoginActivity,
                    "Login failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnLogin.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }
    
    private fun resetPassword(email: String) {
        lifecycleScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                Toast.makeText(
                    this@LoginActivity,
                    "Password reset email sent",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    "Failed to send reset email: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
