plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

gradle.taskGraph.whenReady {
    val prohibited = allTasks.filter {
        val name = it.name.lowercase()
        name.startsWith("install") || name.startsWith("uninstall") ||
            name.startsWith("connected") || name.startsWith("device") ||
            name.startsWith("manageddevice")
    }
    check(prohibited.isEmpty()) {
        "FG-00 is build-only; prohibited device tasks: ${prohibited.joinToString { it.path }}"
    }
}
