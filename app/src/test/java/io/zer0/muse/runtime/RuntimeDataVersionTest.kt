package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 运行时数据版本标记派生测试（纯逻辑）。
 *
 * 锁死：包内 VERSION.txt（打包脚本从真实 package.json 写出）→ 重解压标记
 * 的规范化与历史写死格式一致；解析不了时返回 null 让调用方走兜底，
 * 绝不猜一个版本出来。
 */
class RuntimeDataVersionTest {

    @Test
    fun `marker derives from version txt in the historical format`() {
        assertEquals("npm-12.2.2", RuntimeDataVersion.markerFromVersionTxt("npm 12.2.2"))
        assertEquals("npm-12.2.3", RuntimeDataVersion.markerFromVersionTxt("npm 12.2.3"))
        assertEquals("npm-13.0.0", RuntimeDataVersion.markerFromVersionTxt("npm 13.0.0"))
    }

    @Test
    fun `marker tolerates surrounding whitespace and newline`() {
        assertEquals("npm-12.2.2", RuntimeDataVersion.markerFromVersionTxt("  npm 12.2.2\n"))
        assertEquals("npm-12.2.2", RuntimeDataVersion.markerFromVersionTxt("npm  12.2.2"))
    }

    @Test
    fun `marker keeps prerelease suffixes`() {
        assertEquals("npm-13.0.0-beta.1", RuntimeDataVersion.markerFromVersionTxt("npm 13.0.0-beta.1"))
    }

    @Test
    fun `marker rejects anything that is not an npm version line`() {
        assertNull(RuntimeDataVersion.markerFromVersionTxt(null))
        assertNull(RuntimeDataVersion.markerFromVersionTxt(""))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("   "))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("node 22.0.0"))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("npm"))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("npm latest"))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("npm 12.2"))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("npm 12.2.2 extra"))
        assertNull(RuntimeDataVersion.markerFromVersionTxt("12.2.2"))
    }

    @Test
    fun `version txt entry name matches what the pack script writes`() {
        assertEquals("VERSION.txt", RuntimeDataVersion.VERSION_TXT_ENTRY)
    }
}
