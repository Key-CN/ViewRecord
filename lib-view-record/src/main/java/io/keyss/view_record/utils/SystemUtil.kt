package io.keyss.view_record.utils


/**
 * Description:
 *
 * Time: 2023/12/26 13:53
 * @author Key
 */
object SystemUtil {

    @JvmStatic
    fun isHarmonyOs(): Boolean {
        return try {
            val buildExClass = Class.forName("com.huawei.system.BuildEx")
            val osBrand = buildExClass.getMethod("getOsBrand").invoke(buildExClass) ?: return false
            "Harmony".equals(osBrand.toString(), ignoreCase = true)
        } catch (th: Throwable) {
            false
        }
    }

    /**
     * 获取鸿蒙系统版本号（大版本号）
     * @return 版本号
     */
    fun getHarmonyVersion(): String {
        // [hw_sc.build.platform.version]: [2.0.0]
        return getPropString("hw_sc.build.platform.version", "")
    }

    fun getPlatform(): String {
        // 也许是比较老的未命名平台名的才以cpu名字命名
        // [ro.board.platform]: [kirin710]
        // 以下荣耀举例
        // [ro.board.platform]: [orlando]
        // [ro.boot.hardware]: [kirin810]
        // [ro.hardware]: [kirin810]
        // [ro.product.odm.device]: [kirin810]
        // [ro.product.odm.name]: [kirin810]
        // [ro.product.vendor.device]: [kirin810]
        return getPropString("ro.board.platform", "")
    }

    /**
     * 通过反射获取属性[android.os.SystemProperties]
     * 可以先检查[android.os.Build]有没有提前获取好的静态变量，没有再用反射获取
     */
    fun getPropString(property: String, defaultValue: String): String {
        try {
            val spClz = Class.forName("android.os.SystemProperties")
            val method = spClz.getDeclaredMethod("get", String::class.java)
            val value = method.invoke(spClz, property) as? String
            return value?.takeIf {
                it.isNotBlank()
            } ?: defaultValue
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return defaultValue
    }
}
