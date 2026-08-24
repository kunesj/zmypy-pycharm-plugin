package works.kunesj.plugins.zmypy.services.parser

import works.kunesj.plugins.common.messages.MessageConverter
import works.kunesj.plugins.common.toolWindow.TreeModelDataItem
import works.kunesj.plugins.zmypy.services.mypySeverityConfigs
import works.kunesj.plugins.zmypy.toolWindow.MypyTreeModelDataItem

object MypyMessageConverter : MessageConverter<MypyMessage, TreeModelDataItem> {
    override fun convert(message: MypyMessage): TreeModelDataItem {
        val severity = requireNotNull(mypySeverityConfigs[message.severity]) {
            """Mypy message with type '${message.severity}' is not supported. Please, report this issue at  
                    |https://github.com/kunesj/zmypy-pycharm-plugin/issues""".trimMargin()
        }
        return with(message) {
            MypyTreeModelDataItem(file, line, column, this.message, code, severity, hint)
        }
    }
}