package works.kunesj.plugins.zmypy

import com.intellij.openapi.util.SystemInfo

enum class MypyTool(
    val displayName: String,
    val pipPackage: String,
    val minVersion: String,
    /** SDK mode: module name for `python -m <module>`; null when the tool cannot run as a module */
    val moduleToRun: String?,
    /** SDK mode: executable from the SDK's bin dir; null when `moduleToRun` is used */
    val sdkScriptName: String?,
    val executableFileNames: List<String>,
    val mandatoryArgs: List<String>,
) {
    MYPY(
        "Mypy", "mypy", "1.11", "mypy", null,
        exeNames("mypy", "mypyc"),
        MypyArgs.MANDATORY_ARGS,
    ),
    ZUBAN(
        "Zuban", "zuban", "0.9", null, "zmypy",
        exeNames("zmypy", "zuban"),
        listOf("--show-column-numbers"),
    ),
    ;

    companion object {
        fun fromName(name: String?): MypyTool =
            runCatching { valueOf(name?.uppercase() ?: "") }.getOrDefault(MYPY)
    }
}

// Windows convention: name, name.exe, name.bat
private fun exeNames(vararg names: String): List<String> =
    names.flatMap { name ->
        listOf(name) + if (SystemInfo.isWindows) listOf("$name.exe", "$name.bat") else emptyList()
    }
