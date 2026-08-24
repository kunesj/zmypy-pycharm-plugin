package works.kunesj.plugins.zmypy.annotator

import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.jetbrains.python.inspections.PyInspection

const val MypyInspectionId = "ZMypyInspection"

internal class MypyInspection : PyInspection(), ExternalAnnotatorBatchInspection {

    override fun getShortName(): String = MypyInspectionId
}
