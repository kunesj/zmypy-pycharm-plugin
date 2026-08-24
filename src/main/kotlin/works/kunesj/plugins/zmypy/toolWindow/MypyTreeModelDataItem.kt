package works.kunesj.plugins.zmypy.toolWindow

import works.kunesj.plugins.common.services.SeverityConfig
import works.kunesj.plugins.common.toolWindow.TreeModelDataItem

data class MypyTreeModelDataItem(
    override val file: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val code: String,
    override val severity: SeverityConfig,
    val hint: String?,
) : TreeModelDataItem {
    override fun toRepresentation() = "$message [$code] ($line:$column) ${hint?.replace("\n", " ") ?: ""}"
}