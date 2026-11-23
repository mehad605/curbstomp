package com.mhm.curbstomp.ui.common

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.R
import com.mhm.curbstomp.data.repository.SettingsRepository
import com.mhm.curbstomp.databinding.DialogPasswordBinding
import com.mhm.curbstomp.domain.PasswordHasher
import com.mhm.curbstomp.domain.PasswordLockoutPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet dialogs for setting and verifying Curbstomp's Strict Mode password.
 *
 * - Runs PBKDF2 hashing/verification off the main thread.
 * - Transparently upgrades legacy unsalted SHA-256 hashes to salted PBKDF2 on successful unlock.
 * - Persists exponential brute-force lockout state across process restarts via [PasswordLockoutPolicy].
 */
object PasswordDialog {

    private const val PREFS_NAME = "curbstomp_lockout"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_WALL_MS = "lockout_until_wall_ms"

    @Volatile
    private var inProcessLockoutUntilElapsedMs: Long = 0L

    private fun loadLockoutState(context: Context): PasswordLockoutPolicy.LockoutState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PasswordLockoutPolicy.LockoutState(
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0),
            lockoutUntilWallMs = prefs.getLong(KEY_LOCKOUT_UNTIL_WALL_MS, 0L)
        )
    }

    private fun saveLockoutState(context: Context, state: PasswordLockoutPolicy.LockoutState) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_FAILED_ATTEMPTS, state.failedAttempts)
                putLong(KEY_LOCKOUT_UNTIL_WALL_MS, state.lockoutUntilWallMs)
            }
    }

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

        binding.tvTitle.setText(R.string.dialog_enter_password)
        binding.tilConfirmPassword.visibility = View.GONE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val currentState = loadLockoutState(context)
            val secsLeft = PasswordLockoutPolicy.remainingLockoutSeconds(
                state = currentState,
                nowWallMs = System.currentTimeMillis(),
                lockoutUntilElapsedMs = inProcessLockoutUntilElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
            if (secsLeft > 0L) {
                binding.tilPassword.error = context.getString(R.string.error_too_many_attempts, secsLeft)
                return@setOnClickListener
            }

            val pass = binding.etPassword.text?.toString().orEmpty()
            if (pass.isEmpty()) {
                binding.tilPassword.error = context.getString(R.string.error_required)
                return@setOnClickListener
            }

            binding.tilPassword.error = null
            binding.btnConfirm.isEnabled = false

            owner.lifecycleScope.launch {
                val matched = withContext(Dispatchers.Default) {
                    PasswordHasher.verify(pass, currentHash)
                }

                if (matched) {
                    inProcessLockoutUntilElapsedMs = 0L
                    saveLockoutState(context, PasswordLockoutPolicy.recordSuccess())
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
                    val outcome = PasswordLockoutPolicy.recordFailure(
                        state = loadLockoutState(context),
                        nowWallMs = System.currentTimeMillis()
                    )
                    saveLockoutState(context, outcome.nextState)
                    binding.btnConfirm.isEnabled = true
                    if (outcome.lockoutSeconds > 0L) {
                        inProcessLockoutUntilElapsedMs =
                            SystemClock.elapsedRealtime() + outcome.lockoutSeconds * 1000L
                        binding.tilPassword.error = context.getString(
                            R.string.error_incorrect_password_wait,
                            outcome.lockoutSeconds
                        )
                    } else {
                        binding.tilPassword.error = context.getString(R.string.error_incorrect_password)
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

        binding.tvTitle.setText(R.string.dialog_set_new_password)
        binding.tilConfirmPassword.visibility = View.VISIBLE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val pass = binding.etPassword.text?.toString().orEmpty()
            val confirm = binding.etConfirmPassword.text?.toString().orEmpty()

            if (pass.isEmpty()) {
                binding.tilPassword.error = context.getString(R.string.error_required)
                return@setOnClickListener
            }
            binding.tilPassword.error = null

            if (pass != confirm) {
                binding.tilConfirmPassword.error = context.getString(R.string.error_password_mismatch)
                return@setOnClickListener
            }
            binding.tilConfirmPassword.error = null
            binding.btnConfirm.isEnabled = false

            owner.lifecycleScope.launch {
                val hashed = withContext(Dispatchers.Default) {
                    PasswordHasher.hash(pass)
                }
                settingsRepository.updatePasswordHash(hashed)
                Toast.makeText(context, R.string.toast_password_updated, Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }
}
