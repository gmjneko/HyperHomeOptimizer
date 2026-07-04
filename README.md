# HyperOS 后台堆叠卡片垂直对齐修正

## 背景

HyperOS 桌面后台使用“堆叠”样式时，左侧卡片会比右侧前景卡片更小。这是系统原本的设计，没有问题。

但实际显示中，左侧小卡片的截图主体看起来并没有和右侧卡片垂直居中对齐，而是略微偏上。也就是说，左侧卡片不仅变小了，还产生了视觉上的上移。

## 原因

相关类：

```text
com.miui.home.recents.views.TaskStackViewsAlgorithmStack
com.miui.home.recents.views.TaskViewTransform
com.miui.home.recents.views.TaskView
com.miui.home.recents.views.TaskViewThumbnail
```

堆叠后台中，每张任务卡片的位置和缩放由：

```text
TaskStackViewsAlgorithmStack#getTaskViewTransform(...)
```

计算。

系统会生成一个 `TaskViewTransform`，其中包括：

```text
rect   卡片布局矩形
scale  卡片缩放比例
```

问题在于，`TaskView` 的布局矩形包含顶部 header 区域，而真正的任务截图内容是在 header 下方绘制的。

`TaskViewThumbnail` 内部会把截图绘制区域向下平移一个 header 高度：

```kotlin
canvas.translate(0.0f, getTaskViewHeaderHeight())
```

同时，堆叠样式缩放的是整个 `task_view_wrapper`：

```kotlin
mWrapperView.setScaleX(scale)
mWrapperView.setScaleY(scale)
```

也就是说，缩放中心是整个卡片，而用户实际观察的主体却是 header 下方的截图内容。卡片缩得越小，截图主体的视觉中心就越容易显得偏上。

## 修正思路

不要直接改系统布局参数，也不修改卡片大小，只在 `TaskStackViewsAlgorithmStack#getTaskViewTransform(...)` 执行后，对返回的 `TaskViewTransform.rect` 做一个额外的 Y 方向补偿。

补偿值根据当前卡片与右侧/前景卡片的 scale 差值实时计算：

```kotlin
offsetY = (referenceScale - scale) * headerHeight / 2f
```

其中：

```text
scale           当前卡片的缩放比例
referenceScale  右侧/前景卡片的缩放比例
headerHeight    最近任务卡片 header 高度
```

这样可以保证滑动过程中，右侧卡片变大或变小时，左侧小卡片的补偿也会实时变化。

## Hook 逻辑

Hook 方法：

```text
com.miui.home.recents.views.TaskStackViewsAlgorithmStack#getTaskViewTransform(
    int index,
    float scroll,
    TaskViewTransform transform,
    TaskViewTransform referenceTransform
)
```

在原方法执行后：

1. 读取当前卡片 `transform.scale`
2. 读取当前卡片 `transform.rect`
3. 从 `referenceTransform` 读取前景卡片 scale
4. 读取 `getRecentsTaskViewHeaderHeight()`
5. 对当前卡片 `rect` 执行 Y 方向 offset

核心代码：

```kotlin
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

rect.offset(
    0f,
    (referenceScale - scale) * headerHeight * STACK_CARD_Y_COMPENSATION_MULTIPLIER / 2f
)
```

默认补偿倍率：

```kotlin
private const val STACK_CARD_Y_COMPENSATION_MULTIPLIER = 1f
```

## 影响范围

此修改只 hook：

```text
TaskStackViewsAlgorithmStack
```

因此只影响后台“堆叠”样式。

---

# 感谢
本工程基于 `YuKongA` 的模块模板进行修改，对此表示感谢。
