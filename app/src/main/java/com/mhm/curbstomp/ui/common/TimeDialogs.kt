package com.mhm.curbstomp.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.databinding.DialogTimePickerBinding
import com.mhm.curbstomp.databinding.DialogTimeSelectionBinding
import com.mhm.curbstomp.domain.RuleEngine
import java.util.Locale

/**
 * Shared bottom-sheet time pickers used across rule creation, app limits, and Instant Focus.
 */
object TimeDialogs {

    fun showClockPicker(
        context: Context,
        initialTime24: String,
        onTimeSelected: (String) -> Unit
    ) {
        val totalMinutes = RuleEngine.parseTimeToMinutes(initialTime24) ?: 0
        val hour24 = totalMinutes / 60
        val minute = totalMinutes % 60

        val dialog = BottomSheetDialog(context)
        val binding = DialogTimeSelectionBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.npHours.minValue = 1
        binding.npHours.maxValue = 12

        binding.npMinutes.minValue = 0
        binding.npMinutes.maxValue = 59
        binding.npMinutes.setFormatter { String.format(Locale.US, "%02d", it) }

        binding.npAmPm.minValue = 0
        binding.npAmPm.maxValue = 1
        binding.npAmPm.displayedValues = arrayOf("AM", "PM")

        binding.npAmPm.value = if (hour24 >= 12) 1 else 0
        val displayHour = when (val h = hour24 % 12) {
            0 -> 12
            else -> h
        }
        binding.npHours.value = displayHour
        binding.npMinutes.value = minute

        binding.btnSaveTime.setOnClickListener {
            val isPm = binding.npAmPm.value == 1
            var selectedHour = binding.npHours.value
            if (isPm && selectedHour != 12) {
                selectedHour += 12
            } else if (!isPm && selectedHour == 12) {
                selectedHour = 0
            }
            val formatted = String.format(Locale.US, "%02d:%02d", selectedHour, binding.npMinutes.value)
            onTimeSelected(formatted)
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showDurationOrLimitPicker(
        context: Context,
        initialMinutes: Long,
        title: String? = null,
        confirmLabel: String? = null,
        showFullyBlock: Boolean = false,
        onMinutesSelected: (Long) -> Unit
    ) {
        val clamped = initialMinutes.coerceIn(0L, 23L * 60L + 59L)
        val dialog = BottomSheetDialog(context)
        val binding = DialogTimePickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        if (title != null) {
            binding.tvPickerTitle.text = title
        }
        if (confirmLabel != null) {
            binding.btnSaveLimit.text = confirmLabel
        }
        binding.btnFullyBlock.visibility = if (showFullyBlock) View.VISIBLE else View.GONE

        binding.npHours.minValue = 0
        binding.npHours.maxValue = 23
        binding.npHours.value = (clamped / 60).toInt()

        binding.npMinutes.minValue = 0
        binding.npMinutes.maxValue = 59
        binding.npMinutes.value = (clamped % 60).toInt()

        binding.btnFullyBlock.setOnClickListener {
            onMinutesSelected(0L)
            dialog.dismiss()
        }

        binding.btnSaveLimit.setOnClickListener {
            val totalMinutes = (binding.npHours.value * 60L) + binding.npMinutes.value
            onMinutesSelected(totalMinutes)
            dialog.dismiss()
        }

        dialog.show()
    }
}
