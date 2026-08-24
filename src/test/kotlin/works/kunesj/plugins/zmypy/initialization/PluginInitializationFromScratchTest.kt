package works.kunesj.plugins.zmypy.initialization

import works.kunesj.plugins.zmypy.AbstractToolWindowTestCase
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.testutil.getMypyConfigurationNotCompleteNotification

class PluginInitializationFromScratchTest : AbstractToolWindowTestCase() {

    fun `test plugin initialized from scratch (no python sdk) results in notification`() {
        val actions = getMypyConfigurationNotCompleteNotification(project).actions
        assertEquals(
            ZMypyBundle.message("action.works.kunesj.plugins.zmypy.action.OpenSettingsAction.text"),
            actions.single().templatePresentation.text
        )
    }

    // a persisted state without the `tool` field (pre-2.3.0 settings) must default to mypy
    fun `test tool defaults to mypy from scratch`() {
        assertEquals(MypyTool.MYPY, MypySettings.getInstance(project).tool)
    }
}