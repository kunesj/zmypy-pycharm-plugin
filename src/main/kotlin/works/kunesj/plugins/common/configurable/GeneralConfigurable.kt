package works.kunesj.plugins.common.configurable

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.layout.and
import com.jetbrains.python.sdk.PySdkPopupFactory
import com.jetbrains.python.sdk.noInterpreterMarker
import org.jetbrains.annotations.VisibleForTesting
import works.kunesj.plugins.common.resolveModulePythonSdkNow
import works.kunesj.plugins.common.CommonBundle
import works.kunesj.plugins.common.countPythonSdkModulesNow
import works.kunesj.plugins.common.processErrorAndGet
import works.kunesj.plugins.common.services.AbstractPluginPackageManagementService
import works.kunesj.plugins.common.services.Settings
import works.kunesj.plugins.common.trimToNull
import java.io.File
import java.util.concurrent.Callable
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

data class ConfigurableConfiguration(
    val displayName: String,
    val helpTopic: String,
    val id: String,
    val installActionId: String,
    val installButtonText: String,
    val pickerTitle: String,
    val pickerDirectOptionTitle: String,
    val pickerDirectOptionFileFilter: GeneralConfigurable.FileFilter,
    val pickerDirectOptionEmptyWarning: String,
    val pickerDirectOptionVersionCheckProgressTitle: String,
    val pickerSdkOptionTitle: String,
    val configFilePickerRowComment: String,
    val argumentsDescription: String = "",
    val toolNames: List<String> = emptyList(),
    val toolSelectorLabel: String = "",
    val pickerTitleForTool: ((String) -> String)? = null,
    val directOptionTitleForTool: ((String) -> String)? = null,
    val emptyWarningForTool: ((String) -> String)? = null,
    val installButtonTextForTool: ((String) -> String)? = null,
    val versionCheckTitleForTool: ((String) -> String)? = null,
)

abstract class GeneralConfigurable(
    private val project: Project, @VisibleForTesting val config: ConfigurableConfiguration
) : BoundSearchableConfigurable(config.displayName, config.helpTopic, config.id), Configurable.NoScroll {

    protected abstract val settings: Settings
    protected abstract val packageManager: AbstractPluginPackageManagementService

    abstract fun validateExecutable(path: String?): String?
    abstract suspend fun validateLocalSdk(): String?
    abstract fun validateConfigFilePath(
        builder: ValidationInfoBuilder, field: TextFieldWithBrowseButton
    ): ValidationInfo?

    /** Currently selected tool name ("" when the plugin has no tool selector). */
    protected open fun selectedTool(): String = ""

    /** Persists the selected tool name, invoked when the user changes the tool selector. */
    protected open fun selectTool(tool: String) {}

    private var executablePathError: String? = null
    private lateinit var pathToExecutableField: Cell<TextFieldWithBrowseButton>
    private var sdkError: String? = null
    private var executableRadio: Cell<JBRadioButton>? = null
    private var installButtonCell: Cell<JButton>? = null
    private var currentPickerTitle: String = ""

    private fun pickerTitle(): String = config.pickerTitleForTool?.invoke(selectedTool()) ?: config.pickerTitle
    private fun directOptionTitle(): String =
        config.directOptionTitleForTool?.invoke(selectedTool()) ?: config.pickerDirectOptionTitle
    private fun emptyWarning(): String =
        config.emptyWarningForTool?.invoke(selectedTool()) ?: config.pickerDirectOptionEmptyWarning
    private fun installButtonText(): String =
        config.installButtonTextForTool?.invoke(selectedTool()) ?: config.installButtonText
    private fun versionCheckTitle(): String =
        config.versionCheckTitleForTool?.invoke(selectedTool()) ?: config.pickerDirectOptionVersionCheckProgressTitle

    private fun refreshToolTexts() {
        executableRadio?.component?.text = directOptionTitle()
        installButtonCell?.component?.text = installButtonText()
        val newTitle = pickerTitle()
        if (currentPickerTitle.isNotBlank() && currentPickerTitle != newTitle) {
            findLabelWithText(createComponent(), currentPickerTitle)?.text = newTitle
        }
        currentPickerTitle = newTitle
    }

    private fun findLabelWithText(root: JComponent?, text: String): JLabel? {
        if (root == null) return null
        for (child in root.components) {
            if (child is JLabel && child.text == text) return child
            if (child is JComponent) {
                findLabelWithText(child, text)?.let { return it }
            }
        }
        return null
    }

    private fun validateSdk(): String? {
        if (packageManager.isRemote()) {
            return CommonBundle.message("configurable.remote_sdk_not_supported")
        }
        return runWithModalProgressBlocking(project, CommonBundle.message("configurable.progress.validating_sdk")) {
            validateLocalSdk()
        }
    }

    fun validateWorkingDirectory(builder: ValidationInfoBuilder, field: TextFieldWithBrowseButton): ValidationInfo? {
        val path = field.text.trimToNull() ?: return null
        require(path.isNotBlank())
        val file = File(path)
        if (!file.exists()) {
            return builder.error(CommonBundle.message("configurable.path_to_working_directory.not_exist"))
        }
        if (!file.isDirectory) {
            return builder.error(CommonBundle.message("configurable.path_to_working_directory.is_not_directory"))
        }
        return null
    }

    override fun createPanel(): DialogPanel {
        val pnl = panel {
            indent {
                if (config.toolNames.isNotEmpty()) {
                    toolSelectorRow()
                }
                toolPicker()
                configFilePicker()
                argumentsField()
                workingDirectoryPicker()
                excludeNonProjectFilesCheckbox()
            }
        }
        pnl.registerValidators(disposable!!)
        pnl.validateAll()
        return pnl
    }

    private fun Panel.toolSelectorRow() = row {
        label(config.toolSelectorLabel)
        comboBox(config.toolNames).apply {
            component.selectedItem =
                selectedTool().takeIf { it in config.toolNames } ?: config.toolNames.first()
        }.whenItemSelectedFromUi(disposable!!) { newTool ->
            selectTool(newTool)
            refreshToolTexts()
            (createComponent() as? DialogPanel)?.validateAll()
        }
    }.layout(RowLayout.PARENT_GRID)

    override fun apply() {
        // apply is executed under write lock that we must not block by running an external process
        val futureExecutablePathValidity = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            validateExecutable(pathToExecutableField.component.text)
        })
        executablePathError =
            runWithModalProgressBlocking(project, versionCheckTitle()) {
                futureExecutablePathValidity.get()
            }
        sdkError = validateSdk()
        val validationResults = runCatching {
            (createComponent() as DialogPanel).validateAll()
        }.processErrorAndGet { error(it) }
        if (validationResults.isEmpty()) {
            super.apply()
        }
    }

    class FileFilter(private val fileNames: List<String>) : Condition<VirtualFile> {
        override fun value(t: VirtualFile?): Boolean {
            return fileNames.contains(t?.name ?: return false)
        }
    }

    private fun canInstall(): Boolean {
        return packageManager.canInstallNow()
    }

    private fun Row.installButton(enabled: ComponentPredicate) {
        val buttonClicked = AtomicBooleanProperty(false)
        val action = ActionManager.getInstance().getAction(config.installActionId)
        lateinit var result: Cell<JButton>
        result = button(installButtonText()) {
            val dataContext = DataManager.getInstance().getDataContext(result.component)
            val event = AnActionEvent.createEvent(
                action, dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
            )
            buttonClicked.set(true)
            ActionUtil.performAction(action, event)
        }
        installButtonCell = result
        result.enabledIf(object : ComponentPredicate() {
            override fun invoke() = !buttonClicked.get() && canInstall()
            override fun addListener(listener: (Boolean) -> Unit) {
                buttonClicked.afterChange(listener)
            }
        }.and(enabled))
    }

    private fun Panel.toolPicker() {
        currentPickerTitle = pickerTitle()
        buttonsGroup(title = currentPickerTitle) {
        row {
            val executableOption = radioButton(directOptionTitle(), !USE_PROJECT_SDK)
            executableRadio = executableOption
            executableOption.component
            val executableChooserDescriptor =
                FileChooserDescriptor(true, false, false, false, false, false).withFileFilter(
                    config.pickerDirectOptionFileFilter
                )
            @Suppress("UnstableApiUsage")
            pathToExecutableField = textFieldWithBrowseButton(
                project = project, fileChooserDescriptor = executableChooserDescriptor
            ).align(Align.FILL).bindText(
                getter = { settings.executablePath },
                setter = { settings.executablePath = it.trim() },
            ).validationOnInput { field ->
                executablePathError = null
                if (field.text.isBlank()) {
                    return@validationOnInput warning(emptyWarning())
                }
                null
            }.validationOnApply { field ->
                return@validationOnApply executablePathError.takeIf { field.isEnabled }?.let(::error)
            }.resizableColumn().enabledIf(executableOption.selected)
        }.layout(RowLayout.PARENT_GRID)
        row {
            val sdkOption = radioButton(config.pickerSdkOptionTitle, USE_PROJECT_SDK).enabled(
                project.resolveModulePythonSdkNow() != null
            ).validationOnInput {
                validateSdk()?.let { error(it) }
            }.validationOnApply { field ->
                return@validationOnApply sdkError.takeIf { field.isSelected }?.let(::error)
            }
            sdkOption.component
            label(project.resolveModulePythonSdkNow()?.let { PySdkPopupFactory.shortenNameInPopup(it, 50) }
                ?: noInterpreterMarker).align(
                Align.FILL
            )
            installButton(sdkOption.selected)
        }.rowComment(
            comment = if (project.countPythonSdkModulesNow() > 1) {
                CommonBundle.message("configurable.multiple_sdks_in_use")
            } else if (packageManager.isRemote()) {
                "<code><icon src='AllIcons.General.ExclMark'></code>" + CommonBundle.message("configurable.remote_sdk_not_supported")
            } else if (packageManager.isLocalEnvironment()) {
                ""
            } else if (project.resolveModulePythonSdkNow() != null) {
                CommonBundle.message("configurable.system_wide_installation_warning")
            } else "", maxLineLength = MAX_LINE_LENGTH_WORD_WRAP
        ).layout(RowLayout.PARENT_GRID)
        }.bind(getter = { settings.useProjectSdk }, setter = { settings.useProjectSdk = it })
    }

    private fun Panel.configFilePicker() = row {
        label(CommonBundle.message("configurable.config_file.label"))
        @Suppress("UnstableApiUsage")
        textFieldWithBrowseButton(project = project).align(Align.FILL).bindText(
            getter = { settings.configFilePath },
            setter = { settings.configFilePath = it.trim() },
        ).validationOnApply(::validateConfigFilePath)
            .comment(config.configFilePickerRowComment, maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
    }.layout(RowLayout.PARENT_GRID)

    private fun Panel.argumentsField() = row {
        label(CommonBundle.message("configurable.arguments.label"))
        textField().align(Align.FILL).bindText(
            getter = { settings.arguments },
            setter = { settings.arguments = it.trim() },
        ).comment(config.argumentsDescription, maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
    }.layout(RowLayout.PARENT_GRID)

    private fun Panel.workingDirectoryPicker() = row {
        label(CommonBundle.message("configurable.working_directory.label"))
        val directoryChooserDescriptor = FileChooserDescriptor(false, true, false, false, false, false)
        @Suppress("UnstableApiUsage")
        textFieldWithBrowseButton(
            project = project, fileChooserDescriptor = directoryChooserDescriptor
        ).align(Align.FILL).bindText(
            getter = { settings.workingDirectory ?: "" },
            setter = { settings.workingDirectory = it.ifBlank { null } },
        ).validationOnInput { field ->
            if (field.text.isBlank()) {
                return@validationOnInput warning(CommonBundle.message("configurable.working_directory.empty_warning"))
            }
            null
        }.validationOnApply(::validateWorkingDirectory)
    }.layout(RowLayout.PARENT_GRID)

    private fun Panel.excludeNonProjectFilesCheckbox() = row {
        checkBox(CommonBundle.message("configurable.exclude_non_project_files.label")).bindSelected(
            getter = { settings.excludeNonProjectFiles },
            setter = { settings.excludeNonProjectFiles = it })
    }.layout(RowLayout.PARENT_GRID)

    companion object {
        const val USE_PROJECT_SDK = true
    }
}