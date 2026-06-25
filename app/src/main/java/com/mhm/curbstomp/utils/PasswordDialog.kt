package com.mhm.curbstomp.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.databinding.DialogPasswordBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

object PasswordDialog {

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun verifyPassword(context: Context, dataStoreManager: DataStoreManager, onSuccess: () -> Unit) {
        val settings = dataStoreManager.settings.first()
        val currentHash = settings.passwordHash

        if (currentHash.isNullOrEmpty()) {
            onSuccess()
            return
        }

        val dialog = BottomSheetDialog(context)
        val binding = DialogPasswordBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvTitle.text = "Enter Password"
        binding.tilConfirmPassword.visibility = View.GONE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val pass = binding.etPassword.text.toString()
            if (pass.isEmpty()) {
                binding.tilPassword.error = "Required"
                return@setOnClickListener
            }
            if (hashPassword(pass) == currentHash) {
                dialog.dismiss()
                onSuccess()
            } else {
                binding.tilPassword.error = "Incorrect Password"
            }
        }

        dialog.show()
    }

    suspend fun showSetPasswordDialog(context: Context, dataStoreManager: DataStoreManager) {
        val settings = dataStoreManager.settings.first()
        val hasPassword = !settings.passwordHash.isNullOrEmpty()

        if (hasPassword) {
            verifyPassword(context, dataStoreManager) {
                showSetPasswordDialogInternal(context, dataStoreManager)
            }
        } else {
            showSetPasswordDialogInternal(context, dataStoreManager)
        }
    }

    private fun showSetPasswordDialogInternal(context: Context, dataStoreManager: DataStoreManager) {
        val dialog = BottomSheetDialog(context)
        val binding = DialogPasswordBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvTitle.text = "Set New Password"
        binding.tilConfirmPassword.visibility = View.VISIBLE

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val pass = binding.etPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()

            if (pass.isEmpty()) {
                binding.tilPassword.error = "Required"
                return@setOnClickListener
            }
            if (pass != confirm) {
                binding.tilConfirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                dataStoreManager.updatePasswordHash(hashPassword(pass))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(context, "Password updated successfully", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }
}
