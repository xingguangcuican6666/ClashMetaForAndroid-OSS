package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import java.util.*

class TimeZoneModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val timeZones = receiveBroadcast {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }

        while (true) {
            TimeZone.getDefault()

            timeZones.receive()
        }
    }
}
