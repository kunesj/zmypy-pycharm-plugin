package works.kunesj.plugins.zmypy.configurable

import com.intellij.testFramework.TestDataPath
import works.kunesj.plugins.zmypy.AbstractMypyTestCase
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypySettings
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

@TestDataPath($$"$CONTENT_ROOT/testData/validator")
class MypyValidatorTest : AbstractMypyTestCase() {

    override fun getTestDataPath() = "src/test/testData/validator"

    fun `test obsolete zuban version is rejected`() {
        MypySettings.getInstance(project).tool = MypyTool.ZUBAN
        val path = Paths.get(testDataPath).resolve("zmypy_0_8_0").absolutePathString()
        assertEquals(
            ZMypyBundle.message("mypy.configuration.mypy_invalid_version", "Zuban", "0.9"),
            MypyValidator(project, MypyTool.ZUBAN).validateVersion(path)
        )
    }

    fun `test supported zuban version is accepted`() {
        MypySettings.getInstance(project).tool = MypyTool.ZUBAN
        val path = Paths.get(testDataPath).resolve("zmypy_0_9_0").absolutePathString()
        assertNull(MypyValidator(project, MypyTool.ZUBAN).validateVersion(path))
    }

    fun `test unparseable zuban version is reported`() {
        MypySettings.getInstance(project).tool = MypyTool.ZUBAN
        val path = Paths.get(testDataPath).resolve("zmypy_garbage").absolutePathString()
        assertEquals(
            ZMypyBundle.message("mypy.configuration.path_to_executable.unknown_version", "Zuban"),
            MypyValidator(project, MypyTool.ZUBAN).validateVersion(path)
        )
    }

    fun `test the mypy validator is unaffected by zuban`() {
        // "zuban 0.9.0" is not a mypy version: the mypy validator must flag it as obsolete
        val path = Paths.get(testDataPath).resolve("zmypy_0_9_0").absolutePathString()
        assertEquals(
            ZMypyBundle.message("mypy.configuration.mypy_invalid_version", "Mypy", "1.11"),
            MypyValidator(project, MypyTool.MYPY).validateVersion(path)
        )
    }
}
