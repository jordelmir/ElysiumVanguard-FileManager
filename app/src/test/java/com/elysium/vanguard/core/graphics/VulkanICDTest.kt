package com.elysium.vanguard.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanICDTest {

    @Test
    fun `factory returns TurnipICD for Adreno`() {
        val icd = VulkanICDFactory.create(GPUVendor.ADRENO)
        assertTrue(icd is TurnipICD)
        assertEquals(GPUVendor.ADRENO, icd.vendor)
        assertEquals("vulkan.adreno", icd.nativeLibraryName)
    }

    @Test
    fun `factory returns MaliICD for Mali`() {
        val icd = VulkanICDFactory.create(GPUVendor.MALI)
        assertEquals(GPUVendor.MALI, icd.vendor)
        assertEquals("vulkan.mali", icd.nativeLibraryName)
    }

    @Test
    fun `factory returns PowerVRICD for PowerVR`() {
        val icd = VulkanICDFactory.create(GPUVendor.POWER_VR)
        assertEquals(GPUVendor.POWER_VR, icd.vendor)
    }

    @Test
    fun `factory returns IntelICD for Intel`() {
        val icd = VulkanICDFactory.create(GPUVendor.INTEL)
        assertEquals(GPUVendor.INTEL, icd.vendor)
    }

    @Test
    fun `factory returns AmdICD for AMD`() {
        val icd = VulkanICDFactory.create(GPUVendor.AMD)
        assertEquals(GPUVendor.AMD, icd.vendor)
    }

    @Test
    fun `factory returns NvidiaICD for NVIDIA`() {
        val icd = VulkanICDFactory.create(GPUVendor.NVIDIA)
        assertEquals(GPUVendor.NVIDIA, icd.vendor)
    }

    @Test
    fun `factory returns AppleICD for Apple`() {
        val icd = VulkanICDFactory.create(GPUVendor.APPLE)
        assertEquals(GPUVendor.APPLE, icd.vendor)
    }

    @Test
    fun `factory returns NullICD for Unknown`() {
        val icd = VulkanICDFactory.create(GPUVendor.UNKNOWN)
        assertTrue(icd is NullICD)
        assertEquals(GPUVendor.UNKNOWN, icd.vendor)
    }

    @Test
    fun `TurnipICD reports Vulkan 1_3 capability`() {
        val icd = TurnipICD()
        val cap = icd.loadCapability()
        assertEquals(VulkanApiVersion.VK_1_3, cap.apiVersion)
        assertTrue("Turnip should support Vulkan 1.3", cap.supportsVulkan13)
    }

    @Test
    fun `TurnipICD reports the freedreno extensions`() {
        val icd = TurnipICD()
        val cap = icd.loadCapability()
        assertTrue("VK_KHR_swapchain should be supported",
            cap.supportedExtensions.contains("VK_KHR_swapchain"))
        assertTrue("VK_KHR_dynamic_rendering should be supported",
            cap.supportedExtensions.contains("VK_KHR_dynamic_rendering"))
    }

    @Test
    fun `TurnipICD has the correct metadata`() {
        val icd = TurnipICD()
        assertEquals(GPUVendor.ADRENO, icd.vendor)
        assertEquals("vulkan.adreno", icd.nativeLibraryName)
        assertEquals("Mesa Turnip (freedreno)", icd.displayName)
    }

    @Test
    fun `detect identifies Qualcomm as Adreno`() {
        assertEquals(GPUVendor.ADRENO, GPUVendor.detect("Qualcomm", "Pixel 7"))
    }

    @Test
    fun `detect identifies Adreno in model name`() {
        assertEquals(GPUVendor.ADRENO, GPUVendor.detect("Google", "Pixel 8 Pro Adreno 730"))
    }

    @Test
    fun `detect identifies MediaTek Dimensity as Mali`() {
        assertEquals(GPUVendor.MALI, GPUVendor.detect("MediaTek", "Dimensity 9200"))
    }

    @Test
    fun `detect identifies Samsung Exynos as Mali`() {
        assertEquals(GPUVendor.MALI, GPUVendor.detect("Samsung", "Exynos Mali-G78"))
    }

    @Test
    fun `detect identifies NVIDIA Shield`() {
        assertEquals(GPUVendor.NVIDIA, GPUVendor.detect("NVIDIA", "Shield TV"))
    }

    @Test
    fun `detect identifies Apple as Apple`() {
        assertEquals(GPUVendor.APPLE, GPUVendor.detect("Apple", "MacBook Pro M2"))
    }

    @Test
    fun `detect falls back to UNKNOWN for unrecognized vendors`() {
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.detect("AcmeCorp", "MysteryPhone 9000"))
    }

    @Test
    fun `detect is case insensitive`() {
        assertEquals(GPUVendor.ADRENO, GPUVendor.detect("QUALCOMM", "pixel 7"))
        assertEquals(GPUVendor.MALI, GPUVendor.detect("mediatek", "DIMENSITY 9200"))
    }

    @Test
    fun `VulkanApiVersion 1_3 encoded value matches the spec`() {
        // VK_MAKE_API_VERSION(1, 3, 0) = (1 << 22) | (3 << 12) | 0
        // = 4194304 + 12288 + 0 = 4206592
        val encoded = (1 shl 22) or (3 shl 12)
        assertEquals(VulkanApiVersion.VK_1_3, VulkanApiVersion.fromEncoded(encoded))
    }

    @Test
    fun `VulkanApiVersion 1_2 encoded value matches the spec`() {
        val encoded = (1 shl 22) or (2 shl 12)
        assertEquals(VulkanApiVersion.VK_1_2, VulkanApiVersion.fromEncoded(encoded))
    }

    @Test
    fun `VulkanApiVersion 1_1 encoded value matches the spec`() {
        val encoded = (1 shl 22) or (1 shl 12)
        assertEquals(VulkanApiVersion.VK_1_1, VulkanApiVersion.fromEncoded(encoded))
    }

    @Test
    fun `VulkanApiVersion 1_0 encoded value matches the spec`() {
        val encoded = (1 shl 22) or (0 shl 12)
        assertEquals(VulkanApiVersion.VK_1_0, VulkanApiVersion.fromEncoded(encoded))
    }

    @Test
    fun `VulkanApiVersion unknown for invalid encoded value`() {
        assertEquals(VulkanApiVersion.UNKNOWN, VulkanApiVersion.fromEncoded(0))
        assertEquals(VulkanApiVersion.UNKNOWN, VulkanApiVersion.fromEncoded(2 shl 22))
    }

    @Test
    fun `capability reports supportsVulkan12 true for VK_1_2`() {
        val cap = VulkanCapability(
            apiVersion = VulkanApiVersion.VK_1_2,
            vendor = GPUVendor.ADRENO,
            driverName = "test",
            driverVersion = "1.0.0",
            supportedExtensions = emptySet(),
        )
        assertTrue(cap.supportsVulkan12)
        assertTrue(cap.supportsVulkan11)
        assertFalse(cap.supportsVulkan13)
    }

    @Test
    fun `capability reports supportsVulkan11 false for VK_1_0`() {
        val cap = VulkanCapability(
            apiVersion = VulkanApiVersion.VK_1_0,
            vendor = GPUVendor.ADRENO,
            driverName = "test",
            driverVersion = "1.0.0",
            supportedExtensions = emptySet(),
        )
        assertFalse(cap.supportsVulkan11)
        assertFalse(cap.supportsVulkan12)
    }

    @Test
    fun `NullICD reports unknown API version`() {
        val cap = NullICD().loadCapability()
        assertEquals(VulkanApiVersion.UNKNOWN, cap.apiVersion)
        assertFalse(cap.supportsVulkan11)
    }

    @Test
    fun `factory has 8 vendor mappings`() {
        val vendors = GPUVendor.values()
        assertEquals(8, vendors.size)
        for (vendor in vendors) {
            val icd = VulkanICDFactory.create(vendor)
            assertEquals(vendor, icd.vendor)
            assertNotNull(icd.nativeLibraryName)
            assertTrue(icd.displayName.isNotEmpty())
        }
    }
}
