package com.marvis.mas

import android.app.Application
import com.marvis.mas.server.CodeServerManager
import com.marvis.mas.build.BuildManager

class MASApplication : Application() {

    lateinit var codeServerManager: CodeServerManager
        private set

    lateinit var buildManager: BuildManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        codeServerManager = CodeServerManager(this)
        buildManager = BuildManager(this)
    }

    companion object {
        lateinit var instance: MASApplication
            private set
    }
}
