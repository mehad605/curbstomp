package com.mhm.curbstomp

import android.app.Application

class Curbstomp: Application() {
  override fun onCreate() {
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    super.onCreate()
  }
}
