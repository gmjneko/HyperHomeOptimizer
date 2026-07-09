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

        runCatching { stackTaskViewLayoutOffset(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook stack task view layout config", it) }

        runCatching { searchIndicatorBloomStrokeRemoval(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook search indicator bloom stroke", it) }

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
                    val offset = HORIZONTAL_OFFSET_DP
                    headerButtonPadding.setInt(view, offset)
                    // 仅修改左右 padding，保留原有的上下 padding
                    view.setPadding(offset, view.paddingTop, offset, view.paddingBottom)
                }
            }
    }

    @Volatile private var cachedHeaderHeight = -1f

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
                    val headerHeight = if (cachedHeaderHeight > 0f) {
                        cachedHeaderHeight
                    } else {
                        (getHeaderHeight.invoke(chain.thisObject) as Number).toFloat()
                            .also { cachedHeaderHeight = it }
                    }
                    rect.offset(
                        0f,
                        (referenceScale - scale) * headerHeight * STACK_CARD_Y_COMPENSATION_MULTIPLIER / 2f
                    )
                }
            }
    }

    private fun stackTaskViewLayoutOffset(classLoader: ClassLoader) {
        val stackLayoutConfig = classLoader.loadClass(
            "com.miui.home.recents.layoutconfig.TaskStackLayoutConfig"
        )
        val getCenterY = stackLayoutConfig.getDeclaredMethod("getTaskViewCenterYInWindowFraction")

        hook(getCenterY)
            .setId("stack_task_view_center_y")
            .intercept { TASK_STACK_CENTER_Y_IN_WINDOW_FRACTION }
    }

    private fun searchIndicatorBloomStrokeRemoval(classLoader: ClassLoader) {
        val blurUtilities = classLoader.loadClass(
            "com.miui.home.common.utils.MiuixMaterialBlurUtilities"
        )
        val setSearchIndicatorBlurWithRadius = blurUtilities.getDeclaredMethod(
            "setSearchIndicatorBlurWithRadius",
            View::class.java,
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        val bloomStrokeUtils = classLoader.loadClass("miuix.core.util.HyperBloomStrokeUtils")
        val clearBloomStroke = bloomStrokeUtils
            .getDeclaredMethod("clearBloomStroke", View::class.java)
            .apply { isAccessible = true }

        hook(setSearchIndicatorBlurWithRadius)
            .setId("search_indicator_bloom_stroke_removal")
            .intercept { chain ->
                chain.proceed().also {
                    val view = chain.args[0] as? View ?: return@also
                    val isEnabled = chain.args[1] as? Boolean ?: return@also
                    if (!isEnabled || !isInsideCapsuleIndicator(view)) {
                        return@also
                    }

                    runCatching { clearBloomStroke.invoke(null, view) }
                        .onFailure {
                            log(Log.ERROR, TAG, "Failed to clear search indicator bloom stroke", it)
                        }
                }
            }
    }

    private fun isInsideCapsuleIndicator(view: View): Boolean {
        var current: Any? = view
        while (current != null) {
            if (current.javaClass.name == CAPSULE_INDICATOR_CLASS) return true
            current = (current as? View)?.parent
        }
        return false
    }

    private companion object {
        private const val TAG = "miui-home"
        private const val MIUI_HOME_PACKAGE = "com.miui.home"
        private const val CAPSULE_INDICATOR_CLASS =
            "com.miui.home.launcher.pageindicators.CapsuleIndicator"
        private const val HORIZONTAL_OFFSET_DP = 45
        private const val STACK_CARD_Y_COMPENSATION_MULTIPLIER = 1.58f
        private const val TASK_STACK_CENTER_Y_IN_WINDOW_FRACTION = 0.49f
    }
}
