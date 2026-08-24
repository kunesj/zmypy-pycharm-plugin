package works.kunesj.plugins.zmypy.testutil

import com.intellij.notification.Notification
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.test.notification.getConfigurationNotCompleteNotification
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.services.MypySettings

fun getMypyConfigurationNotCompleteNotification(project: Project): Notification =
    getConfigurationNotCompleteNotification(
        project,
        ZMypyBundle.message("notification.group.mypy.group"),
        ZMypyBundle.message("mypy.notification.incomplete_configuration", MypySettings.getInstance(project).tool.displayName)
    )
