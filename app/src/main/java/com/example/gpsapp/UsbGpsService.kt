package com.example.gpsapp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

class UsbGpsService(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun connectToGps(onData: (String) -> Unit) {
        val availableDrivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.e("UsbGpsService", "No USB serial devices found")
            return
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.e("UsbGpsService", "Permission denied to open USB device")
            return
        }

        serialPort = driver.ports[0]
        serialPort?.apply {
            open(connection)
            setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            executor.submit {
                val buffer = ByteArray(1024)
                while (true) {
                    try {
                        val len = read(buffer, 1000)
                        if (len > 0) {
                            val data = String(buffer, 0, len)
                            Log.d("UsbGpsService", "Received: $data")
                            onData(data)
                        }
                    } catch (e: Exception) {
                        Log.e("UsbGpsService", "Error reading USB", e)
                        break
                    }
                }
            }
        }
    }

    fun disconnect() {
        serialPort?.close()
        executor.shutdownNow()
    }
}
