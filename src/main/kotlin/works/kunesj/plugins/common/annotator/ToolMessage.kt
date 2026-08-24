package works.kunesj.plugins.common.annotator

interface ToolMessage {
    val message: String
    val line: Int
    val column: Int
}