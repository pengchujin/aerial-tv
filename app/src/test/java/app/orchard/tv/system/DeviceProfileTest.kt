package app.orchard.tv.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
class DeviceProfileTest {
    private val json = java.io.File("src/main/assets/devices.json").readText()

    /** The entries as the Kotlin table had them before they moved to devices.json. */
    @Test fun builtInEntriesMatchTheOldTable() {
        val nsop0 = DeviceProfile.match(json, "MiTV-NSOP0", 22)!!
        assertEquals(mapOf(6 to "HDMI 1", 5 to "HDMI 2", 4 to "HDMI 3", 3 to "TV", 1 to "DTMB", 0 to "AV"), nsop0.labels)
        assertEquals(mapOf(6 to 23, 5 to 24, 4 to 25), nsop0.xiaomiSourceIds)

        val mftr0 = DeviceProfile.match(json, "MiTV_MFTR0", 0)!!
        assertEquals(setOf(8), mftr0.hiddenHardware)
        assertEquals(mapOf(1 to "ATV", 0 to "DTMB", 2 to "AV", 6 to "HDMI 2 (eARC)"), mftr0.labels)

        val mffu1 = DeviceProfile.match(json, "MiTV-MFFU1", 0)!!
        assertEquals(mapOf(9 to "DP", 10 to "USB-C"), mffu1.labels)
        assertEquals(mapOf(9 to 29, 10 to 30), mffu1.xiaomiSourceIds)

        assertEquals(mapOf(6 to 23), DeviceProfile.match(json, "MiTV_ANTL0", 0)!!.xiaomiSourceIds)

        val mfzp0 = DeviceProfile.match(json, "MiTV-MFZP0", 0)!!
        assertEquals(setOf(7), mfzp0.hiddenHardware)
        assertEquals(mapOf(4 to 23, 5 to 24, 6 to 25), mfzp0.xiaomiSourceIds)

        assertTrue(DeviceProfile.match(json, "MiTV_MFTP0", 0)!!.ignoresPictureBacklight)
        // Whaley WTV43K1J lets its launcher show at boot (#902); nothing else does.
        assertEquals(1000, DeviceProfile.match(json, "WTV43K1J", 0)!!.bootStockMs)
        assertEquals(0, DeviceProfile.match(json, "MiTV-NSOP0", 22)!!.bootStockMs)
        assertNull(DeviceProfile.match(json, "MiTV-MFTP1", 0))
    }

    /** MiTV4-ANSM0: the fallback only below firmware 22 (#375). */
    @Test fun firmwareConditionHolds() {
        assertEquals(mapOf(7 to 23), DeviceProfile.match(json, "MiTV4-ANSM0", 8)!!.xiaomiFallbackSourceIds)
        assertEquals(mapOf(7 to 23), DeviceProfile.match(json, "MiTV4A", 21)!!.xiaomiFallbackSourceIds)
        assertNull(DeviceProfile.match(json, "MiTV4-ANSM0", 22, 4))
        // From 22.8 the registered HDMI3 service is the HDMI 1 port, source 23 (#592, #584).
        assertEquals(mapOf(7 to 23, 6 to 24, 5 to 25), DeviceProfile.match(json, "MiTV4-ANSM0", 22, 8)!!.xiaomiSourceIds)
        assertEquals(mapOf(7 to 23, 6 to 24, 5 to 25), DeviceProfile.match(json, "MiTV4-ANSM0", 22, 11)!!.xiaomiSourceIds)
        assertEquals("HDMI 1", DeviceProfile.match(json, "MiTV4-ANSM0", 23, 0)!!.labels[7])
    }

    @Test fun changhongRequiresBothTheReportedModelAndProduct() {
        val profile = DeviceProfile.match(json, "长虹智能电视", 0, product = "mt5520_ll")!!
        assertTrue(profile.changhongTvos)
        assertFalse(profile.isEmpty)
        assertNull(DeviceProfile.match(json, "长虹智能电视", 0))
        assertNull(DeviceProfile.match(json, "长虹智能电视", 0, product = "other_product"))
        assertNull(DeviceProfile.match(json, "Other TV", 0, product = "mt5520_ll"))
        assertNull(DeviceProfile.match(json, "mt5520_ll", 0, product = "mt5520_ll"))
        assertFalse(DeviceProfile.match(json, "MiTV_MFTR0", 0, product = "mt5520_ll")!!.changhongTvos)
        assertFalse(DeviceProfile.parse(JSONObject()).changhongTvos)
    }

    @Test fun jsonRoundTrips() {
        val p = DeviceProfile(setOf(8, 3), mapOf(6 to "HDMI 1"), mapOf(6 to 23), mapOf(7 to 25), true, bootStockMs = 1500, changhongTvos = true)
        val back = DeviceProfile.parse(JSONObject(p.toJson().toString()))
        assertEquals(p.hiddenHardware, back.hiddenHardware)
        assertEquals(p.labels, back.labels)
        assertEquals(p.xiaomiSourceIds, back.xiaomiSourceIds)
        assertEquals(p.xiaomiFallbackSourceIds, back.xiaomiFallbackSourceIds)
        assertTrue(back.ignoresPictureBacklight)
        assertEquals(1500, back.bootStockMs)
        assertTrue(back.changhongTvos)
        assertTrue(DeviceProfile().isEmpty)
        assertFalse(DeviceProfile(bootStockMs = 1000).isEmpty)
        assertFalse(DeviceProfile(filterHomeAlways = true).isEmpty)
        assertTrue(DeviceProfile.parse(JSONObject(DeviceProfile(filterHomeAlways = true).toJson().toString())).filterHomeAlways)
        assertFalse(back.filterHomeAlways)
        assertEquals("{}", DeviceProfile().toJson().toString())
    }

    @Test fun ownProfileReplacesAndResets() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(DeviceProfile.setOwn(context, """{"hidden":[8],"xiaomiSources":{"6":24},"changhongTvos":true}"""))
        assertEquals(DeviceProfile.Origin.ThisTv, DeviceProfile.origin(context))
        assertFalse(DeviceProfile.current(context).changhongTvos)
        assertEquals(mapOf(6 to 24), DeviceProfile.current(context).xiaomiSourceIds)

        assertFalse(DeviceProfile.setOwn(context, "not json"))
        assertEquals(mapOf(6 to 24), DeviceProfile.current(context).xiaomiSourceIds)
        assertFalse(DeviceProfile.setOwn(context, """{"labels":{"HW6":"x"}}"""))

        assertTrue(DeviceProfile.setOwn(context, null as String?))
        assertTrue(DeviceProfile.origin(context) != DeviceProfile.Origin.ThisTv)
        assertFalse(DeviceProfile.current(context).changhongTvos)
    }

    @Test fun ownProfileCannotEnableChanghongOnOtherModelsOrProducts() {
        val context = RuntimeEnvironment.getApplication()
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product", "MiTV_MFTR0" to "mt5520_ll")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            val own = """{"hidden":[8],"labels":{"6":"HDMI 2"},"xiaomiSources":{"6":24},"filterHomeAlways":true,"changhongTvos":true}"""
            assertTrue(DeviceProfile.setOwn(context, own))
            val stored = DeviceProfile.ownJson(context)
            val profile = DeviceProfile.current(context)
            assertFalse(profile.changhongTvos)
            assertFalse(profile.toJson().has("changhongTvos"))
            assertTrue(profile.filterHomeAlways)
            assertTrue(profile.toJson().getBoolean("filterHomeAlways"))
            assertEquals(setOf(8), profile.hiddenHardware)
            assertEquals(mapOf(6 to "HDMI 2"), profile.labels)
            assertEquals(mapOf(6 to 24), profile.xiaomiSourceIds)
            assertEquals(DeviceProfile.Origin.ThisTv, DeviceProfile.origin(context))
            assertEquals(stored, DeviceProfile.ownJson(context))
        }
    }

    @Test fun matchingTvCanKeepOrDisableChanghongInItsOwnProfile() {
        val context = RuntimeEnvironment.getApplication()
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        assertTrue(DeviceProfile.setOwn(context, """{"changhongTvos":true}"""))
        assertTrue(DeviceProfile.current(context).changhongTvos)
        assertTrue(DeviceProfile.setOwn(context, "{}"))
        assertFalse(DeviceProfile.current(context).changhongTvos)
        assertTrue(DeviceProfile.setOwn(context, null))
        assertTrue(DeviceProfile.current(context).changhongTvos)
    }
}
