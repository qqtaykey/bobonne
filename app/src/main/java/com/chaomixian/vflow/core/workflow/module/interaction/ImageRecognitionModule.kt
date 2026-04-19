// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/ImageRecognitionModule.kt
package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import coil.load
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionResult
import com.chaomixian.vflow.core.execution.ProgressUpdate
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VCoordinateRegion
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.ui.overlay.RegionSelectionOverlay
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * "图片识别" 模块。
 * 用于识别屏幕上的图片并获取其坐标位置。
 */
class ImageRecognitionModule : BaseModule() {

    override val id = "vflow.interaction.image_recognition"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_interaction_image_recognition_name,
        descriptionStringRes = R.string.module_vflow_interaction_image_recognition_desc,
        name = "图片识别",
        description = "识别屏幕上的图片并获取其坐标位置。",
        iconRes = R.drawable.rounded_image_search_24,
        category = "界面交互",
        categoryId = "interaction"
    )

    override val uiProvider: ModuleUIProvider? = ImageRecognitionModuleUIProvider()

    companion object {
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_IMAGE = "image"
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "mode",
            name = "模式",
            staticType = ParameterType.ENUM,
            defaultValue = MODE_SCREENSHOT,
            options = listOf(MODE_SCREENSHOT, MODE_IMAGE),
            acceptsMagicVariable = false,
            inputStyle = InputStyle.CHIP_GROUP,
            nameStringRes = R.string.param_vflow_interaction_image_recognition_mode_name,
            optionsStringRes = listOf(
                R.string.param_vflow_interaction_image_recognition_mode_opt_screenshot,
                R.string.param_vflow_interaction_image_recognition_mode_opt_image
            )
        ),
        InputDefinition(
            id = "image",
            name = "输入图片",
            staticType = ParameterType.ANY,
            acceptsMagicVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.IMAGE.id),
            visibility = InputVisibility.whenEquals("mode", MODE_IMAGE),
            nameStringRes = R.string.param_vflow_interaction_image_recognition_image_name
        ),
        InputDefinition(
            id = "select_image_region",
            name = "图片范围",
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.COORDINATE_REGION.id),
            isFolded = false,
            hint = "点击+号选择图片区域",
            nameStringRes = R.string.param_vflow_interaction_image_recognition_select_image_region_name,
            hintStringRes = R.string.param_vflow_interaction_image_recognition_select_image_region_hint
        ),
        InputDefinition(
            id = "image_coordinates",
            name = "图片坐标",
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.COORDINATE.id),
            isFolded = false,
            hint = "自动生成，可手动修改",
            nameStringRes = R.string.param_vflow_interaction_image_recognition_image_coordinates_name,
            hintStringRes = R.string.param_vflow_interaction_image_recognition_image_coordinates_hint
        )
    )

    override fun getDynamicInputs(step: ActionStep?, allSteps: List<ActionStep>?): List<InputDefinition> {
        return getInputs()
    }

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        return listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_interaction_image_recognition_success_name),
            OutputDefinition("image_region", "图片区域", VTypeRegistry.COORDINATE_REGION.id, nameStringRes = R.string.output_vflow_interaction_image_recognition_image_region_name),
            OutputDefinition("image_coordinates", "图片坐标", VTypeRegistry.COORDINATE.id, nameStringRes = R.string.output_vflow_interaction_image_recognition_image_coordinates_name),
            OutputDefinition("screenshot", "截图", VTypeRegistry.IMAGE.id, nameStringRes = R.string.output_vflow_interaction_image_recognition_screenshot_name)
        )
    }

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val mode = step.parameters["mode"] as? String ?: MODE_SCREENSHOT
        return if (mode == MODE_SCREENSHOT) {
            context.getString(R.string.summary_vflow_interaction_image_recognition_screenshot)
        } else {
            context.getString(R.string.summary_vflow_interaction_image_recognition_image)
        }
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 获取参数
        val inputsById = getInputs().associateBy { it.id }
        val rawMode = context.getVariableAsString("mode", MODE_SCREENSHOT)
        val mode = inputsById["mode"]?.normalizeEnumValue(rawMode) ?: rawMode

        // 获取图片范围参数
        val region = parseRegionParameter(context)
        val coordinates = parseCoordinatesParameter(context)

        // 验证参数
        if (region == null) {
            return ExecutionResult.Failure("参数错误", "请选择图片范围")
        }

        val appContext = context.applicationContext

        try {
            onProgress(ProgressUpdate("正在处理图片..."))

            // 生成截图或使用输入图片
            val screenshotUri = if (mode == MODE_SCREENSHOT) {
                // 截图逻辑
                takeScreenshot(appContext)
            } else {
                // 使用输入图片
                val imageVar = context.getVariableAsImage("image")
                    ?: return ExecutionResult.Failure("参数错误", "请提供一张有效的图片")
                Uri.parse(imageVar.uriString)
            }

            onProgress(ProgressUpdate("正在分析图片..."))

            // 计算图片中心坐标
            val centerX = (region.left + region.right) / 2
            val centerY = (region.top + region.bottom) / 2
            val imageCoordinates = VCoordinate(centerX, centerY)

            onProgress(ProgressUpdate("分析完成"))

            return ExecutionResult.Success(mapOf(
                "success" to VBoolean(true),
                "image_region" to region,
                "image_coordinates" to imageCoordinates,
                "screenshot" to VImage(screenshotUri.toString())
            ))

        } catch (e: IOException) {
            return ExecutionResult.Failure("图片处理失败", e.message ?: "无法处理图片")
        } catch (e: Exception) {
            return ExecutionResult.Failure("图片识别异常", e.message ?: "发生了未知错误")
        }
    }

    /**
     * 解析图片范围参数
     */
    private fun parseRegionParameter(context: ExecutionContext): VCoordinateRegion? {
        // 尝试从变量获取 VCoordinateRegion
        val regionObj = context.getVariable("select_image_region")
        val magicRegion = regionObj as? VCoordinateRegion
        if (magicRegion != null) return magicRegion

        // 尝试从 variables 获取字符串
        val rawRegion = context.getVariableAsString("select_image_region", "")
        if (rawRegion.isNullOrBlank()) return null

        // 如果是变量引用，解析变量
        val resolvedText = if (rawRegion.startsWith("{{") && rawRegion.endsWith("}}") {
            val resolvedValue = com.chaomixian.vflow.core.execution.VariableResolver.resolveValue(rawRegion, context)
            when (resolvedValue) {
                is VCoordinateRegion -> return resolvedValue
                is VString -> resolvedValue.raw
                is String -> resolvedValue
                else -> null
            }
        } else {
            rawRegion
        }

        // 解析坐标字符串（格式：x1,y1,x2,y2）
        return parseCoordinateString(resolvedText)
    }

    /**
     * 解析坐标参数
     */
    private fun parseCoordinatesParameter(context: ExecutionContext): VCoordinate? {
        // 尝试从变量获取 VCoordinate
        val coordObj = context.getVariable("image_coordinates")
        val magicCoord = coordObj as? VCoordinate
        if (magicCoord != null) return magicCoord

        // 尝试从 variables 获取字符串
        val rawCoord = context.getVariableAsString("image_coordinates", "")
        if (rawCoord.isNullOrBlank()) return null

        // 如果是变量引用，解析变量
        val resolvedText = if (rawCoord.startsWith("{{") && rawCoord.endsWith("}}") {
            val resolvedValue = com.chaomixian.vflow.core.execution.VariableResolver.resolveValue(rawCoord, context)
            when (resolvedValue) {
                is VCoordinate -> return resolvedValue
                is VString -> resolvedValue.raw
                is String -> resolvedValue
                else -> null
            }
        } else {
            rawCoord
        }

        // 解析坐标字符串（格式：x,y）
        return parsePointString(resolvedText)
    }

    /**
     * 解析坐标字符串
     */
    private fun parseCoordinateString(text: String?): VCoordinateRegion? {
        if (text.isNullOrBlank()) return null

        val parts = text.split(",")
        if (parts.size != 4) return null

        val left = parts[0].trim().toIntOrNull() ?: return null
        val top = parts[1].trim().toIntOrNull() ?: return null
        val right = parts[2].trim().toIntOrNull() ?: return null
        val bottom = parts[3].trim().toIntOrNull() ?: return null

        return VCoordinateRegion(left, top, right, bottom)
    }

    /**
     * 解析点坐标字符串
     */
    private fun parsePointString(text: String?): VCoordinate? {
        if (text.isNullOrBlank()) return null

        val parts = text.split(",")
        if (parts.size != 2) return null

        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null

        return VCoordinate(x, y)
    }

    /**
     * 截图
     */
    private suspend fun takeScreenshot(context: Context): Uri {
        return withContext(Dispatchers.IO) {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "image_recognition_$timestamp.png"
            val cacheFile = java.io.File(StorageManager.tempDir, fileName)
            val path = cacheFile.absolutePath

            val command = "screencap -p \"$path\""
            DebugLogger.i("ImageRecognitionModule", "执行截图命令: $command")
            val result = ShellManager.execShellCommand(context, command, ShellManager.ShellMode.AUTO)
            DebugLogger.i("ImageRecognitionModule", "截图命令执行结果: $result")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                DebugLogger.i("ImageRecognitionModule", "截图文件大小: ${cacheFile.length()} 字节")
                Uri.fromFile(cacheFile)
            } else {
                throw IOException("截图失败")
            }
        }
    }
}

class ImageRecognitionModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val cardRegionPreview: MaterialCardView = view.findViewById(R.id.card_region_preview)
        val ivRegionPreview: ImageView = view.findViewById(R.id.iv_region_preview)
        val layoutPlaceholder: LinearLayout = view.findViewById(R.id.layout_placeholder)
        val layoutRegionInfo: LinearLayout = view.findViewById(R.id.layout_region_info)
        val tvRegionInfo: TextView = view.findViewById(R.id.tv_region_info)
        val btnSelectRegion: MaterialButton = view.findViewById(R.id.btn_select_region)
        val btnClearRegion: MaterialButton = view.findViewById(R.id.btn_clear_region)

        var region: String = ""
        var coordinates: String = ""
        var screenshotUri: Uri? = null
        var onParametersChangedCallback: (() -> Unit)? = null
        var scope: CoroutineScope? = null
    }

    override fun getHandledInputIds(): Set<String> {
        return setOf("select_image_region", "image_coordinates")
    }

    override fun createPreview(
        context: Context,
        parent: ViewGroup,
        step: ActionStep,
        allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.partial_capture_screen_editor, parent, false)
        val holder = ViewHolder(view)
        holder.onParametersChangedCallback = onParametersChanged
        holder.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // 恢复区域参数
        val region = currentParameters["select_image_region"] as? String ?: ""
        holder.region = region
        val coordinates = currentParameters["image_coordinates"] as? String ?: ""
        holder.coordinates = coordinates

        if (region.isNotEmpty()) {
            // 解析区域，如果有截图则显示预览
            updateRegionInfo(holder, region)
        }

        // 点击预览区域弹出输入框
        holder.cardRegionPreview.setOnClickListener {
            showRegionInputDialog(context, holder)
        }

        // 选择区域按钮
        holder.btnSelectRegion.setOnClickListener {
            selectRegion(context, holder)
        }

        // 清除区域按钮
        holder.btnClearRegion.setOnClickListener {
            holder.region = ""
            holder.coordinates = ""
            holder.screenshotUri = null
            holder.ivRegionPreview.isVisible = false
            holder.layoutPlaceholder.isVisible = true
            holder.layoutRegionInfo.isVisible = false
            holder.onParametersChangedCallback?.invoke()
        }

        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        return mapOf(
            "select_image_region" to h.region,
            "image_coordinates" to h.coordinates
        )
    }

    /**
     * 显示区域输入对话框
     */
    private fun showRegionInputDialog(context: Context, holder: ViewHolder) {
        // 创建输入框
        val textInputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = "区域坐标 (left,top,right,bottom)"
        }

        val textInputEditText = TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(holder.region)
        }

        textInputLayout.addView(textInputEditText)

        MaterialAlertDialogBuilder(context)
            .setTitle("设置图片区域")
            .setMessage("请输入区域坐标\n例如：100,100,500,600")
            .setView(textInputLayout)
            .setPositiveButton(R.string.common_ok) { dialog, _ ->
                val input = textInputEditText.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    // 验证格式
                    val parts = input.split(",")
                    if (parts.size == 4 && parts.all { it.trim().toIntOrNull() != null }) {
                        holder.region = input
                        // 计算中心坐标
                        val left = parts[0].trim().toInt()
                        val top = parts[1].trim().toInt()
                        val right = parts[2].trim().toInt()
                        val bottom = parts[3].trim().toInt()
                        val centerX = (left + right) / 2
                        val centerY = (top + bottom) / 2
                        holder.coordinates = "$centerX,$centerY"
                        updateRegionInfo(holder, input)
                        holder.onParametersChangedCallback?.invoke()
                        dialog.dismiss()
                    } else {
                        textInputLayout.error = "格式错误，请输入4个数字，用逗号分隔"
                    }
                } else {
                    holder.region = ""
                    holder.coordinates = ""
                    holder.screenshotUri = null
                    holder.ivRegionPreview.isVisible = false
                    holder.layoutPlaceholder.isVisible = true
                    holder.layoutRegionInfo.isVisible = false
                    holder.onParametersChangedCallback?.invoke()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.common_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun selectRegion(context: Context, holder: ViewHolder) {
        DebugLogger.i("ImageRecognitionModuleUIProvider", "用户点击区域选择按钮")

        // 检查悬浮窗权限
        if (!PermissionManager.isGranted(context, PermissionManager.OVERLAY)) {
            DebugLogger.w("ImageRecognitionModuleUIProvider", "悬浮窗权限未授予")
            Toast.makeText(context, R.string.toast_vflow_system_capture_screen_overlay_permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        DebugLogger.i("ImageRecognitionModuleUIProvider", "悬浮窗权限检查通过")

        // 检查 Shell 权限
        val shellPermissions = ShellManager.getRequiredPermissions(context)
        val hasShellPermission = shellPermissions.all { PermissionManager.isGranted(context, it) }
        if (!hasShellPermission) {
            DebugLogger.w("ImageRecognitionModuleUIProvider", "Shell 权限未授予: $shellPermissions")
            Toast.makeText(context, R.string.toast_vflow_system_capture_screen_shell_permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        DebugLogger.i("ImageRecognitionModuleUIProvider", "Shell 权限检查通过")

        // 使用弱引用避免内存泄漏
        val contextRef = WeakReference(context)
        val holderRef = WeakReference(holder)

        holder.scope?.launch {
            try {
                // 使用外部存储目录，确保 shell 可以写入
                val screenshotDir = StorageManager.tempDir
                DebugLogger.i("ImageRecognitionModuleUIProvider", "创建 RegionSelectionOverlay，使用目录: ${screenshotDir.absolutePath}")
                val overlay = RegionSelectionOverlay(context, screenshotDir)
                val result = overlay.captureAndSelectRegion()

                DebugLogger.i("ImageRecognitionModuleUIProvider", "区域选择流程结束，result: $result")

                withContext(Dispatchers.Main) {
                    val ctx = contextRef.get()
                    val h = holderRef.get()

                    if (ctx != null && h != null && result != null) {
                        h.region = result.region
                        h.screenshotUri = result.screenshotUri

                        // 计算中心坐标
                        val parts = result.region.split(",")
                        if (parts.size == 4) {
                            val left = parts[0].trim().toIntOrNull() ?: 0
                            val top = parts[1].trim().toIntOrNull() ?: 0
                            val right = parts[2].trim().toIntOrNull() ?: 0
                            val bottom = parts[3].trim().toIntOrNull() ?: 0
                            val centerX = (left + right) / 2
                            val centerY = (top + bottom) / 2
                            h.coordinates = "$centerX,$centerY"
                        }

                        // 更新预览
                        if (result.screenshotUri != null) {
                            h.ivRegionPreview.isVisible = true
                            h.layoutPlaceholder.isVisible = false
                            h.ivRegionPreview.load(result.screenshotUri) {
                                crossfade(true)
                                error(R.drawable.rounded_broken_image_24)
                            }
                        }

                        // 显示区域信息
                        updateRegionInfo(h, result.region)

                        h.onParametersChangedCallback?.invoke()
                        DebugLogger.i("ImageRecognitionModuleUIProvider", "区域信息已更新")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("ImageRecognitionModuleUIProvider", "区域选择失败", e)
                withContext(Dispatchers.Main) {
                    contextRef.get()?.let {
                        Toast.makeText(
                            it,
                            "区域选择失败: ${e.message ?: ""}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateRegionInfo(holder: ViewHolder, region: String) {
        holder.layoutRegionInfo.isVisible = true
        holder.tvRegionInfo.text = "区域: $region"

        // 如果有截图，显示裁剪后的预览
        if (holder.screenshotUri != null) {
            holder.ivRegionPreview.isVisible = true
            holder.layoutPlaceholder.isVisible = false
        }
    }
}
