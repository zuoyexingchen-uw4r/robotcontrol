package com.robomaster.control.comm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB-OTG 串口通道（ESP32-S3，CH343 走 USB-CDC）。
 *
 * 关键点：
 * 1. usb-serial-for-android 的 CdcAcmSerialDriver 直接支持 CDC 模式的 CH343；
 *    若固件烧的是厂商 2.0 模式，则用下方自定义 Prober 按 VID/PID 匹配 CH34x 驱动。
 * 2. Android 12+ 申请 USB 权限的 PendingIntent 必须 FLAG_MUTABLE，否则收不到回调。
 * 3. 波特率按与电控约定的来（这里默认 921600，CH343 最高 6M）。
 */
class UsbSerialTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    baudRateInit: Int = 115_200,
) : Transport {

    /** 波特率，可运行时修改（下次 connect 生效）；需求默认 115200 */
    @Volatile var baudRate: Int = baudRateInit

    /** 忽略 VID/PID，强制用通用 CDC 驱动接管指定/首个 USB 设备（子菜单开关） */
    @Volatile var ignoreDeviceId: Boolean = false

    /** 强制接管时指定 UsbDevice.deviceName（如 /dev/bus/usb/001/004），为空取首个设备 */
    @Volatile var forcedDeviceName: String? = null

    override val name = "USB-OTG"
    override val state = MutableStateFlow<ConnState>(ConnState.Idle)
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            synchronized(this@UsbSerialTransport) { (this@UsbSerialTransport as Object).notifyAll() }
        }
    }

    @Volatile private var granted = false

    private val wchVid = 0x1A86

    /** 自定义 Prober：显式登记 WCH 全系（CH343 走 CDC-ACM，CH340 走厂商模式），默认表没覆盖也能抓 */
    private fun prober(): UsbSerialProber {
        val table = ProbeTable()
        table.addProduct(wchVid, 0x55D3, CdcAcmSerialDriver::class.java) // CH343 CDC-ACM
        table.addProduct(wchVid, 0x55D4, CdcAcmSerialDriver::class.java) // CH343 CDC-ACM
        table.addProduct(wchVid, 0x7522, Ch34xSerialDriver::class.java)  // CH340 厂商模式
        table.addProduct(wchVid, 0x7523, Ch34xSerialDriver::class.java)  // CH340 厂商模式
        return UsbSerialProber(table)
    }

    /** 拿到设备并申请 OTG 权限（会阻塞等待用户授权） */
    private suspend fun acquirePort(): UsbSerialPort = withContext(Dispatchers.Main) {
        val device: UsbDevice = if (ignoreDeviceId) {
            (forcedDeviceName?.let { name -> usbManager.deviceList.values.firstOrNull { it.deviceName == name } }
                ?: usbManager.deviceList.values.firstOrNull())
                ?: throw IllegalStateException("未发现可接管的 USB 设备")
        } else {
            usbManager.deviceList.values.firstOrNull { it.vendorId == wchVid }
                ?: throw IllegalStateException("未发现 WCH(0x1A86) USB 设备（确认 OTG 线连接）")
        }
        val driver: UsbSerialDriver = if (ignoreDeviceId) {
            CdcAcmSerialDriver(device) // 强制接管：通用 CDC 驱动打开，绕过 VID/PID 匹配
        } else {
            prober().probeDevice(device)
                ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: CdcAcmSerialDriver(device)
        }
        if (!usbManager.hasPermission(device)) {
            granted = false
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            usbManager.requestPermission(device, pi)
            // 等授权（最多 15s）
            val deadline = System.currentTimeMillis() + 15_000
            while (!granted && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (!granted) throw IllegalStateException("用户拒绝了 USB 权限")
        }
        driver.ports.firstOrNull() ?: throw IllegalStateException("驱动未暴露可用串口端口")
    }

    override suspend fun connect() {
        val p = acquirePort()
        val connection = usbManager.openDevice(p.driver.device)
            ?: throw IllegalStateException("无法打开 USB 设备连接")
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // DTR/RTS 拉起，让 ESP32-S3 正常进入串口工作
        p.dtr = true
        p.rts = true
        port = p
        state.value = ConnState.Connected(name)

        // 读循环（IO 线程）：不断把字节推给 incoming
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            try {
                while (state.value is ConnState.Connected) {
                    val n = p.read(buf, 50)
                    if (n > 0) incoming.emit(buf.copyOf(n))
                }
            } catch (e: Exception) {
                state.value = ConnState.Error(name, e.message ?: "read error")
            } finally {
                runCatching { p.close() }
            }
        }
    }

    override suspend fun disconnect() {
        runCatching { port?.close() }
        port = null
        state.value = ConnState.Idle
        runCatching { context.unregisterReceiver(permissionReceiver) }
    }

    override suspend fun send(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val p = port ?: return@withContext false
        try {
            p.write(bytes, 100)
            true
        } catch (e: Exception) {
            state.value = ConnState.Error(name, e.message ?: "write error")
            false
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.robomaster.control.USB_PERMISSION"
    }
}
