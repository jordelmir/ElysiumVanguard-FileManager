package com.elysium.vanguard.core.ai

/** Allow-listed reproducible build entry points available inside a mounted workspace. */
object AgentBuildPolicy {
    fun script(target: String, variant: String?): String = when (target) {
        "android" -> when (variant?.lowercase()) {
            "debug" -> "./gradlew :app:assembleDebug"
            "release" -> "./gradlew :app:assembleRelease"
            else -> throw IllegalArgumentException("Android builds require debug or release variant")
        }
        "make" -> {
            require(variant.isNullOrBlank() || variant == "default") { "Make supports only the default variant" }
            "make"
        }
        "cmake" -> when (variant?.lowercase()) {
            "debug" -> "cmake -S . -B build/elysium-debug -DCMAKE_BUILD_TYPE=Debug && cmake --build build/elysium-debug"
            "release" -> "cmake -S . -B build/elysium-release -DCMAKE_BUILD_TYPE=Release && cmake --build build/elysium-release"
            else -> throw IllegalArgumentException("CMake builds require debug or release variant")
        }
        else -> throw IllegalArgumentException("Unsupported build target")
    }
}
