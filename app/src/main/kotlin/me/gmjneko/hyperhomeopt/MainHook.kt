package me.gmjneko.hyperhomeopt

import android.graphics.RectF
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam


class MainHook : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != MIUI_HOME_PACKAGE) return

        runCatching { taskViewHeaderOffset(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook TaskViewHeader", it) }

        runCatching { stackTaskViewVisualCenterOffset(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook TaskStackViewsAlgorithmStack", it) }

    }

    private fun taskViewHeaderOffset(classLoader: ClassLoader) {
        val taskViewHeader = classLoader.loadClass(
            "com.miui.home.recents.views.TaskViewHeader"
        )
        val onAttachedToWindow = taskViewHeader.getDeclaredMethod("onAttachedToWindow")
        val headerButtonPadding = taskViewHeader
            .getDeclaredField("mHeaderButtonPadding")
            .apply { isAccessible = true }

        hook(onAttachedToWindow)
            .setId("task_view_header_offset")
            .intercept { chain ->
                chain.proceed().also {
                    val view = chain.thisObject as View
                    // 将 dp 转换为 px，保证不同分辨率设备表现一致
                    val offset = HORIZONTAL_OFFSET_DP
                    headerButtonPadding.setInt(view, offset)
                    // 仅修改左右 padding，保留原有的上下 padding
                    view.setPadding(offset, view.paddingTop, offset, view.paddingBottom)
                }
            }
    }

    private fun stackTaskViewVisualCenterOffset(classLoader: ClassLoader) {
        val stackAlgorithm = classLoader.loadClass(
            "com.miui.home.recents.views.TaskStackViewsAlgorithmStack"
        )
        val taskViewTransform = classLoader.loadClass(
            "com.miui.home.recents.views.TaskViewTransform"
        )
        val getTaskViewTransform = stackAlgorithm.getDeclaredMethod(
            "getTaskViewTransform",
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            taskViewTransform,
            taskViewTransform,
        )
        val scaleField = taskViewTransform
            .getDeclaredField("scale")
            .apply { isAccessible = true }
        val rectField = taskViewTransform
            .getDeclaredField("rect")
            .apply { isAccessible = true }
        val visibleField = taskViewTransform
            .getDeclaredField("visible")
            .apply { isAccessible = true }
        val getHeaderHeight = stackAlgorithm.getMethod("getRecentsTaskViewHeaderHeight")

        hook(getTaskViewTransform)
            .setId("stack_task_view_visual_center_offset")
            .intercept { chain ->
                chain.proceed().also {
                    val transform = chain.args[2]
                    val referenceTransform = chain.args[3]
                    val scale = scaleField.getFloat(transform)
                    val referenceScale = if (
                        referenceTransform != null && visibleField.getBoolean(referenceTransform)
                    ) {
                        scaleField.getFloat(referenceTransform)
                    } else {
                        1f
                    }

                    val rect = rectField.get(transform) as RectF
                    val headerHeight = (getHeaderHeight.invoke(chain.thisObject) as Number).toFloat()
                    rect.offset(0f, (referenceScale - scale) * headerHeight * STACK_CARD_Y_COMPENSATION_MULTIPLIER / 2f)
                }
            }
    }

    private companion object {
        private const val TAG = "miui-home"
        private const val MIUI_HOME_PACKAGE = "com.miui.home"
        private const val HORIZONTAL_OFFSET_DP = 45
        private const val STACK_CARD_Y_COMPENSATION_MULTIPLIER = 1.4f
    }
}
