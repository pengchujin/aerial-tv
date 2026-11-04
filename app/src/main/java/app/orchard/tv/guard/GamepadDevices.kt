package app.orchard.tv.guard

/** Filters firmware input placeholders without disabling protection for real gamepads. */
internal object GamepadDevices {
    private const val AZFP0_EVENTSERVER_NAME = "eventserver-Joystick"
    private const val AZFP0_EVENTSERVER_VENDOR_ID = 0xdead
    private const val AZFP0_EVENTSERVER_PRODUCT_ID = 0xbeef

    // The built-in remote receiver of Amlogic TVs: not external, vendor and product 0x0001, yet
    // its sources include SOURCE_GAMEPAD. Counted as a gamepad, it made the guard stop filtering
    // keys in other apps (issue #774's Android 11 rule), so Home went to PatchWall first and Aerial
    // came back seconds later (MiTV-AZFP0, Android 9, 2026-10-30).
    private const val AMLOGIC_KEYPAD_NAME = "aml_keypad"
    private const val AMLOGIC_KEYPAD_VENDOR_ID = 0x0001
    private const val AMLOGIC_KEYPAD_PRODUCT_ID = 0x0001

    // The Bluetooth voice remote paired to Redmi Projector Lite (Projector_10, Android 9) declares
    // SOURCE_GAMEPAD. Counting it as a gamepad made the guard stop filtering keys outside Aerial;
    // the firmware's normal Home dispatch then force-stopped Aerial and cold-started it.
    private const val REDMI_PROJECTOR_REMOTE_NAME = "小米蓝牙语音遥控器"
    private const val XIAOMI_VENDOR_ID = 0x2717
    private const val REDMI_PROJECTOR_REMOTE_PRODUCT_ID = 0x32b8
    // Its built-in input wrapper also declares SOURCE_GAMEPAD despite being a keyboard/remote.
    private const val HILISILICON_PROJECTOR_KEYBOARD_NAME = "Hi keyboard"
    private const val HILISILICON_PROJECTOR_KEYBOARD_VENDOR_ID = 0x0001
    private const val HILISILICON_PROJECTOR_KEYBOARD_PRODUCT_ID = 0x0001

    fun isPhysicalGamepad(
        name: String?,
        isVirtual: Boolean,
        supportsGamepad: Boolean,
        vendorId: Int,
        productId: Int,
        alphabetic: Boolean = false,
    ): Boolean {
        if (isVirtual || name.orEmpty().contains("virtual", ignoreCase = true) || !supportsGamepad) return false
        // A full keyboard is not a gamepad, whatever buttons it also declares. TCL's input stand-ins
        // for its phone remote, "sim-kb" and "sim-mouse", are alphabetic keyboards with gamepad
        // buttons, there with nothing plugged in; counted as gamepads, they stopped the filter in
        // other apps and Home there showed the TCL launcher for Android's 5 s hold (tcl_m7642,
        // Android 9, issue #1234: the reporter's dumpsys input, 4.9 s in 1.63-1.82, instant in 1.62).
        if (alphabetic) return false

        // MiTV-AZFP0 (Android 9) reports these software eventserver endpoints as gamepads.
        if (name == AZFP0_EVENTSERVER_NAME &&
            vendorId == AZFP0_EVENTSERVER_VENDOR_ID && productId == AZFP0_EVENTSERVER_PRODUCT_ID
        ) return false
        if (name == AMLOGIC_KEYPAD_NAME &&
            vendorId == AMLOGIC_KEYPAD_VENDOR_ID && productId == AMLOGIC_KEYPAD_PRODUCT_ID
        ) return false
        if (name == REDMI_PROJECTOR_REMOTE_NAME &&
            vendorId == XIAOMI_VENDOR_ID && productId == REDMI_PROJECTOR_REMOTE_PRODUCT_ID
        ) return false
        if (name == HILISILICON_PROJECTOR_KEYBOARD_NAME &&
            vendorId == HILISILICON_PROJECTOR_KEYBOARD_VENDOR_ID &&
            productId == HILISILICON_PROJECTOR_KEYBOARD_PRODUCT_ID
        ) return false

        return true
    }

    /**
     * A mouse plugged in or paired: external, not virtual, with SOURCE_MOUSE. With the key filter
     * on, a Bluetooth mouse moving hung system_server the way #774's gamepad did and the TV
     * restarted (MiTV-MFTR0, Android 11, issue #988: the reporter's watchdog records, and no hang
     * with the guard really off). Built-in pointers are left out: TCL MT5879's internal
     * "gIrTouch_Mouse" reports MOUSE | TOUCHPAD but is not external.
     */
    fun isExternalMouse(isExternal: Boolean, isVirtual: Boolean, supportsMouse: Boolean): Boolean =
        isExternal && !isVirtual && supportsMouse
}
