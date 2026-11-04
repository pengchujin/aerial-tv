package app.orchard.tv.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadDevicesTest {
    @Test fun filtersAzfp0EventserverJoystickPlaceholder() {
        assertFalse(GamepadDevices.isPhysicalGamepad("eventserver-Joystick", false, true, 0xdead, 0xbeef))
    }

    @Test fun keepsSameNamedDeviceWithDifferentHardwareIds() {
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Joystick", false, true, 0x1234, 0x5678))
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Joystick", false, true, 0xdead, 0x5678))
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Joystick", false, true, 0x1234, 0xbeef))
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Joystick", false, true, 0, 0))
    }

    @Test fun doesNotExcludeOtherEventserverNames() {
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Gamepad", false, true, 0xdead, 0xbeef))
        assertTrue(GamepadDevices.isPhysicalGamepad("eventserver-Joystick Pro", false, true, 0xdead, 0xbeef))
    }

    @Test fun keepsOtherPhysicalGamepads() {
        assertTrue(GamepadDevices.isPhysicalGamepad("USB Gamepad", false, true, 0x1234, 0x5678))
    }

    @Test fun rejectsExistingVirtualDeviceCases() {
        assertFalse(GamepadDevices.isPhysicalGamepad("USB Gamepad", true, true, 0x1234, 0x5678))
        assertFalse(GamepadDevices.isPhysicalGamepad("virtual-search", false, true, 0, 0))
    }

    @Test fun filtersAmlogicBuiltInRemoteReceiver() {
        assertFalse(GamepadDevices.isPhysicalGamepad("aml_keypad", false, true, 0x0001, 0x0001))
    }

    @Test fun keepsSameNamedKeypadWithOtherIds() {
        assertTrue(GamepadDevices.isPhysicalGamepad("aml_keypad", false, true, 0x045e, 0x028e))
        assertTrue(GamepadDevices.isPhysicalGamepad("aml_keypad pro", false, true, 0x0001, 0x0001))
    }

    @Test fun filtersRedmiProjectorBluetoothRemote() {
        assertFalse(GamepadDevices.isPhysicalGamepad("小米蓝牙语音遥控器", false, true, 0x2717, 0x32b8))
        assertTrue(GamepadDevices.isPhysicalGamepad("USB Gamepad", false, true, 0x2717, 0x32b8))
        assertTrue(GamepadDevices.isPhysicalGamepad("小米蓝牙语音遥控器", false, true, 0x2717, 0x0001))
    }

    @Test fun filtersRedmiProjectorBuiltInKeyboardWrapper() {
        assertFalse(GamepadDevices.isPhysicalGamepad("Hi keyboard", false, true, 0x0001, 0x0001))
        assertTrue(GamepadDevices.isPhysicalGamepad("Hi keyboard", false, true, 0x046d, 0xc21f))
    }

    @Test fun alphabeticKeyboardsAreNotGamepads() {
        // TCL tcl_m7642's phone-remote stand-ins (issue #1234).
        assertFalse(GamepadDevices.isPhysicalGamepad("sim-kb", false, true, 0, 0, alphabetic = true))
        assertFalse(GamepadDevices.isPhysicalGamepad("sim-mouse", false, true, 0, 0, alphabetic = true))
        assertTrue(GamepadDevices.isPhysicalGamepad("Xbox Wireless Controller", false, true, 0x045e, 0x0b13, alphabetic = false))
    }

    @Test fun rejectsDevicesWithoutGamepadSource() {
        assertFalse(GamepadDevices.isPhysicalGamepad("Keyboard", false, false, 0x1234, 0x5678))
    }

    @Test fun externalMouseCounts() {
        assertTrue(GamepadDevices.isExternalMouse(isExternal = true, isVirtual = false, supportsMouse = true))
    }

    @Test fun builtInPointerAndOthersDoNot() {
        // TCL MT5879's gIrTouch_Mouse: MOUSE | TOUCHPAD, not external.
        assertFalse(GamepadDevices.isExternalMouse(isExternal = false, isVirtual = false, supportsMouse = true))
        assertFalse(GamepadDevices.isExternalMouse(isExternal = true, isVirtual = true, supportsMouse = true))
        // Xiaomi's Bluetooth remote: external, keyboard and dpad only.
        assertFalse(GamepadDevices.isExternalMouse(isExternal = true, isVirtual = false, supportsMouse = false))
    }
}
