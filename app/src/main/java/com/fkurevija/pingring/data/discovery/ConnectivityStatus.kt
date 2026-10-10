package com.fkurevija.pingring.data.discovery

data class ConnectivityStatus(
    val isBluetoothEnabled: Boolean,
    val isWifiEnabled: Boolean,
    val supportsBleIdentity: Boolean = true,
)
