package works.kunesj.plugins.zmypy.testutil

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil.performAction
import org.junit.Assert
import works.kunesj.plugins.common.test.action.updateActionForTest
import works.kunesj.plugins.zmypy.action.InstallMypyAction
import works.kunesj.plugins.zmypy.action.ScanAction
import works.kunesj.plugins.zmypy.action.StopScanAction

fun scan(context: DataContext) {
    val action = ActionManager.getInstance().getAction(ScanAction.ID)
    val event = AnActionEvent.createEvent(context, null, "", ActionUiKind.NONE, null)
    updateActionForTest(action, event)
    Assert.assertTrue(event.presentation.isEnabled)
    performAction(action, event)
}

fun stopScan(context: DataContext) {
    val action = ActionManager.getInstance().getAction(StopScanAction.ID)
    val event = AnActionEvent.createEvent(context, null, ActionPlaces.EDITOR_TAB, ActionUiKind.NONE, null)
    updateActionForTest(action, event)
    Assert.assertTrue(event.presentation.isEnabled)
    performAction(action, event)
}

fun installMypy(context: DataContext) {
    val action = ActionManager.getInstance().getAction(InstallMypyAction.ID)
    val event = AnActionEvent.createEvent(context, null, ActionPlaces.NOTIFICATION, ActionUiKind.NONE, null)
    updateActionForTest(action, event)
    Assert.assertTrue(event.presentation.isEnabled)
    performAction(action, event)
}
