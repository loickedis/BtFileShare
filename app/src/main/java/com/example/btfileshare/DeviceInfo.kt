package com.example.btfileshare

data class DeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int = Int.MIN_VALUE,
    var isApple: Boolean = false,
    var paired: Boolean = false
) {
    val displayName: String get() = name.ifBlank { "Appareil inconnu" }

    companion object {
        private val APPLE_NAMES = listOf(
            "IPHONE", "IPAD", "MACBOOK", "IMAC", "MAC MINI", "MAC PRO", "MAC STUDIO",
            "APPLE WATCH", "AIRPODS", "HOMEPOD", "APPLE TV"
        )

        fun isAppleName(name: String?): Boolean {
            val upper = name?.uppercase() ?: return false
            return APPLE_NAMES.any { upper.contains(it) }
        }
    }
}
