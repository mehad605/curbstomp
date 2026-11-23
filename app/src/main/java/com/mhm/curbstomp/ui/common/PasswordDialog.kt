package com.mhm.curbstomp.ui.common

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.data.repository.SettingsRepository
import com.mhm.curbstomp.databinding.DialogPasswordBinding
import com.mhm.curbstomp.domain.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet dialogs for setting and verifying Curbstomp's Strict Mode password.
 *
 * - Runs PBKDF2 hashing/verification off the main thread.
 * - Transparently upgrades legacy unsalted SHA-256 hashes to salted PBKDF2 on successful unlock.
 * - Applies exponential brute-force backoff after 3 consecutive failed attempts.
 */
object PasswordDialog {

    private const val MAX_FREE_ATTEMPTS = 3
    private const val BASE_LOCKOUT_MS = 3_000L
    private const val MAX_LOCKOUT_MS = 60_000L

    private var failedAttempts: Int = 0
    private var lockoutUntilElapsedMs: Long = 0L

    suspend fun verifyPassword(
        context: Context,
        settingsRepository: SettingsRepository,
        onSuccess: () -> Unit
    ) {
        val currentHash = settingsRepository.settings.first().passwordHash
        if (currentHash.isNullOrEmpty()) {
            onSuccess()
            return
        }

        val owner = context as? LifecycleOwner ?: return
        val dialog = BottomSheetDialog(context)
        val binding = DialogPasswordBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvTitle.text = "Enter Password"
        binding.tilConfirmPassword.visibility = View.GONE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now < lockoutUntilElapsedMs) {
                val secsLeft = ((lockoutUntilElapsedMs - now) + 999L) / 1000L
                binding.tilPassword.error = "Too many attempts. Try again in ${secsLeft}s"
                return@setOnClickListener
            }

            val pass = binding.etPassword.text?.toString().orEmpty()
            if (pass.isEmpty()) {
                binding.tilPassword.error = "Required"
                return@setOnClickListener
            }

            binding.tilPassword.error = null
            binding.btnConfirm.isEnabled = false

            owner.lifecycleScope.launch {
                val matched = withContext(Dispatchers.Default) {
                    PasswordHasher.verify(pass, currentHash)
                }

                if (matched) {
                    failedAttempts = 0
                    lockoutUntilElapsedMs = 0L
                    if (PasswordHasher.needsUpgrade(currentHash)) {
                        val upgraded = withContext(Dispatchers.Default) {
                            PasswordHasher.hash(pass)
                        }
                        settingsRepository.updatePasswordHash(upgraded)
                    }
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    onSuccess()
                } else {
                    failedAttempts++
                    binding.btnConfirm.isEnabled = true
                    if (failedAttempts >= MAX_FREE_ATTEMPTS) {
                        val exponent = (failedAttempts - MAX_FREE_ATTEMPTS).coerceAtMost(5)
                        val lockoutMs = (BASE_LOCKOUT_MS shl exponent).coerceAtMost(MAX_LOCKOUT_MS)
                        lockoutUntilElapsedMs = SystemClock.elapsedRealtime() + lockoutMs
                        val secs = lockoutMs / 1000L
                        binding.tilPassword.error = "Incorrect password. Wait ${secs}s"
                    } else {
                        binding.tilPassword.error = "Incorrect Password"
                    }
                }
            }
        }

        dialog.show()
    }

    suspend fun showSetPasswordDialog(
        context: Context,
        settingsRepository: SettingsRepository
    ) {
        val hasPassword = !settingsRepository.settings.first().passwordHash.isNullOrEmpty()
        if (hasPassword) {
            verifyPassword(context, settingsRepository) {
                showSetPasswordDialogInternal(context, settingsRepository)
            }
        } else {
            showSetPasswordDialogInternal(context, settingsRepository)
        }
    }

    private fun showSetPasswordDialogInternal(
        context: Context,
        settingsRepository: SettingsRepository
    ) {
        val owner = context as? LifecycleOwner ?: return
        val dialog = BottomSheetDialog(context)
        val binding = DialogPasswordBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvTitle.text = "Set New Password"
        binding.tilConfirmPassword.visibility = View.VISIBLE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val pass = binding.etPassword.text?.toString().orEmpty()
            val confirm = binding.etConfirmPassword.text?.toString().orEmpty()

            if (pass.isEmpty()) {
                binding.tilPassword.error = "Required"
                return@setOnClickListener
            }
            binding.tilPassword.error = null

            if (pass != confirm) {
                binding.tilConfirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }
            binding.tilConfirmPassword.error = null
            binding.btnConfirm.isEnabled = false

            owner.lifecycleScope.launch {
                val hashed = withContext(Dispatchers.Default) {
                    PasswordHasher.hash(pass)
                }
                settingsRepository.updatePasswordHash(hashed)
                Toast.makeText(context, "Password updated successfully", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }
}
