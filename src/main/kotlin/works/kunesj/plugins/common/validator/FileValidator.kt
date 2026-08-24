package works.kunesj.plugins.common.validator

import works.kunesj.plugins.common.CommonBundle
import java.io.File

object FileValidator {
    fun validateConfigFilePath(path: String?): String? {
        if (path == null) return null
        require(path.isNotBlank())
        val file = File(path)
        if (!file.exists()) {
            return CommonBundle.message("file_validator.not_exists")
        }
        if (file.isDirectory) {
            return CommonBundle.message("file_validator.is_directory")
        }
        return null
    }
}