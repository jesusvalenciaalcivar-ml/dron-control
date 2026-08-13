package com.jesus.dronplataneras

import android.app.Application
import android.content.Context

class MyApplication : Application(){
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
}