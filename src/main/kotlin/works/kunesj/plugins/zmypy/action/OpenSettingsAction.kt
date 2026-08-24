package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.options.BoundSearchableConfigurable
import works.kunesj.plugins.common.action.AbstractOpenSettingsAction
import works.kunesj.plugins.zmypy.configurable.MypyConfigurable

class OpenSettingsAction : AbstractOpenSettingsAction() {
    override fun getConfigurableClass(): Class<out BoundSearchableConfigurable> = MypyConfigurable::class.java

    companion object {
        const val ID = "works.kunesj.plugins.zmypy.action.OpenSettingsAction"
    }
}
