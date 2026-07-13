package com.modspec.agent

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ModspecApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        LsposedCli.init(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        XposedServiceCoordinator.onServiceBind(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            XposedServiceCoordinator.onServiceDied()
        }
    }

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set
    }
}
