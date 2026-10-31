package tk.glucodata.drivers.icanhealth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback

/** Exercises the real discovery/read callbacks; native storage and the radio are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class,
    shadows = [ICanRebindNatives::class, ICanRebindGatt::class])
class ICanHealthAddressRebindTests {
    private val sensor = "7BF00099C663AABB"
    private val oldAddress = "AA:BB:CC:DD:EE:01"
    private val candidateAddress = "AA:BB:CC:DD:EE:02"
    private lateinit var manager: ICanHealthBleManager
    private lateinit var oldDevice: BluetoothDevice
    private lateinit var gatt: BluetoothGatt
    private lateinit var radio: ICanRebindGatt
    private lateinit var context: Applic

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        ICanHealthRegistry.promoteSensorIdentity(context, sensor, sensor, oldAddress, sensor)
        manager = ICanHealthBleManager(sensor, 0L)
        oldDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(oldAddress)
        manager.setDevice(oldDevice)
        set("serialFromDevice", sensor)
        set("rawSerialFromDevice", sensor)
        set("vendorModelName", "iCan i3")
        set("vendorFirmwareVersion", "1.0")
        set("vendorSoftwareVersion", "1.0")
        set("createdAtMs", 0L)
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
        manager.setDevice(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(candidateAddress))
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        radio = Shadow.extract(gatt)
        radio.remoteDevice = manager.mActiveBluetoothDevice
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
    }

    @After fun tearDown() {
        manager.destroy()
    }

    @Test fun missingCgmServiceRestoresDeviceAddressAndSavedIdentity() {
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        // A late callback from the rejected peripheral must not replace the saved identity.
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
    }

    @Test fun missingSerialCharacteristicCannotUseCachedIdentity() {
        addCgmSurface(withSerial = false)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        assertTrue(radio.reads.isEmpty())
    }

    @Test fun cachedMetadataStillReadsSerialAndPersistsAcceptedAddressAcrossRestart() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(ICanHealthConstants.SERIAL_NUMBER), radio.reads)
        assertEquals(oldAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        deliverSerial(sensor)
        assertEquals(candidateAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        assertEquals(sensor, manager.SerialNumber)
        assertNull(get("pendingAddressRebind"))
        val restored = ICanHealthRegistry.createRestoredCallback(context, sensor, 0L) as ICanHealthBleManager
        try {
            assertEquals(candidateAddress, restored.mActiveDeviceAddress)
        } finally {
            restored.destroy()
        }
        // A cancelled deadline cannot undo an accepted address.
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertEquals(candidateAddress, manager.mActiveDeviceAddress)
    }

    @Test fun differentSerialDoesNotRewriteIdentityOrPreferenceKeys() {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val authKey = ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX + sensor
        prefs.edit().putString(authKey, "123456789").commit()
        addCgmSurface(withAuth = true)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
        assertEquals("123456789", prefs.getString(authKey, null))
        assertNull(ICanHealthRegistry.findRecord(context, "7BF00099C664AABB"))
    }

    @Test fun failedSerialReadRollsBackBeforeAuthenticationOrSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial(sensor, BluetoothGatt.GATT_FAILURE)
        assertRolledBack()
    }

    @Test fun emptySerialReadRollsBackBeforeAuthenticationOrSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("")
        assertRolledBack()
    }

    @Test fun serialReadThatCannotStartRollsBack() {
        addCgmSurface()
        radio.readStarts = false
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
    }

    @Test fun serialReadTimeoutRollsBackInsteadOfDrainingIntoSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        (get("gattOpTimeoutRunnable") as Runnable).run()
        assertRolledBack()
    }

    @Test fun failedDiscoveryRestoresOriginalDevice() {
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        assertRolledBack()
    }

    @Test fun silentCandidateDeadlineRestoresOriginalDevice() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertRolledBack()
    }

    @Test fun disconnectBeforeSerialVerificationRestoresOriginalDevice() {
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        assertRolledBack()
    }

    @Test fun anotherScanCannotReplaceCandidateWhileIdentityReadIsPending() {
        assertFalse(manager.matchDeviceName(sensor, "AA:BB:CC:DD:EE:03"))
        manager.setDevice(oldDevice)
        assertEquals(candidateAddress, manager.mActiveDeviceAddress)
        assertEquals(candidateAddress, manager.mActiveBluetoothDevice.address)
    }

    @Test fun transientTimeoutPermitsAnotherAttemptAfterBackoff() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertRolledBack()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
    }

    @Test fun identityMismatchRemainsRejectedAfterBackoff() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
    }

    @Test fun provisionalIdentityCannotAuthorizeAnotherAddress() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        manager.SerialNumber = "ICN-7BF00099C663"
        set("serialFromDevice", null)
        set("rawSerialFromDevice", null)
        assertFalse(manager.matchDeviceName("ICN-7BF00099C663", "AA:BB:CC:DD:EE:03"))
        assertNull(get("pendingAddressRebind"))
        assertEquals(oldAddress, manager.mActiveDeviceAddress)
    }

    private fun assertRolledBack() {
        assertEquals(oldAddress, manager.mActiveDeviceAddress)
        assertSame(oldDevice, manager.mActiveBluetoothDevice)
        assertEquals(sensor, manager.SerialNumber)
        assertEquals(sensor, get("serialFromDevice"))
        assertEquals(sensor, get("rawSerialFromDevice"))
        assertEquals(oldAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        assertEquals(oldAddress, ICanRebindNatives.address)
        assertNull(get("pendingAddressRebind"))
        assertEquals(ICanHealthBleManager.Phase.IDLE, manager.phase)
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
        assertTrue(radio.closed)
    }

    private fun addCgmSurface(withSerial: Boolean = true, withAuth: Boolean = false) {
        val cgm = BluetoothGattService(ICanHealthConstants.CGM_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        cgm.addCharacteristic(BluetoothGattCharacteristic(ICanHealthConstants.CGM_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0))
        if (withAuth) {
            val auth = BluetoothGattCharacteristic(ICanHealthConstants.CGM_SPECIFIC_OPS,
                BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            auth.addDescriptor(BluetoothGattDescriptor(ICanHealthConstants.CCCD,
                BluetoothGattDescriptor.PERMISSION_WRITE))
            cgm.addCharacteristic(auth)
        }
        radio.discoveredServices.add(cgm)
        if (withSerial) {
            val dis = BluetoothGattService(ICanHealthConstants.DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            dis.addCharacteristic(BluetoothGattCharacteristic(ICanHealthConstants.SERIAL_NUMBER,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            radio.discoveredServices.add(dis)
        }
    }

    @Suppress("DEPRECATION")
    private fun deliverSerial(serial: String, status: Int = BluetoothGatt.GATT_SUCCESS) {
        val characteristic = BluetoothGattCharacteristic(ICanHealthConstants.SERIAL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        characteristic.value = serial.toByteArray(Charsets.US_ASCII)
        manager.onCharacteristicRead(gatt, characteristic, status)
    }

    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)

    private fun set(name: String, value: Any?) = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
}

class ICanRebindApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ICanRebindNatives {
    companion object {
        var address: String? = null
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun setDeviceAddress(dataptr: Long, value: String?) { address = value }
    }
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class ICanRebindGatt {
    lateinit var remoteDevice: BluetoothDevice
    val discoveredServices = mutableListOf<BluetoothGattService>()
    val reads = mutableListOf<UUID>()
    var readStarts = true
    var closed = false
    @Implementation fun getDevice(): BluetoothDevice = remoteDevice
    @Implementation fun getServices(): List<BluetoothGattService> = discoveredServices
    @Implementation fun getService(uuid: UUID): BluetoothGattService? = discoveredServices.firstOrNull { it.uuid == uuid }
    @Implementation fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        reads.add(characteristic.uuid)
        return readStarts
    }
    @Implementation fun disconnect() {}
    @Implementation fun close() { closed = true }
}
