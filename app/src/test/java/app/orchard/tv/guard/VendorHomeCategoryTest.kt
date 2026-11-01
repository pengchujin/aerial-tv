package app.orchard.tv.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorHomeCategoryTest {
    @Test fun vendorHomes() {
        assertTrue(HomeGuardService.isVendorHomeCategory("android.intent.category.HOME.CC"))
        assertTrue(HomeGuardService.isVendorHomeCategory("com.tcl.category.TCL_HOME"))
    }

    @Test fun notHomes() {
        assertFalse(HomeGuardService.isVendorHomeCategory("android.intent.category.SECONDARY_HOME"))
        // CVTE's factory test (issue #1028).
        assertFalse(HomeGuardService.isVendorHomeCategory("android.intent.category.CVTE_AT_HOME"))
        assertFalse(HomeGuardService.isVendorHomeCategory("android.intent.category.LAUNCHER"))
    }
}
