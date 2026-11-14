package com.xiaowan.localinference

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 模型库那三个对话框的**界面构造**（第 7 批 H5 的 ModelDialogs.kt）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一层
 * ══════════════════════════════════════════════════════════════════════════
 * 别名 / 改类型 / 槽位不匹配这三个对话框原本长在 [EngineActivity] 里，
 * 而它们共同的特点是：**构造界面**与**保存后的状态迁移**是两件事。
 * 前者只依赖 `Context`、取色表与 dp 换算；后者要动 `modelFile` /
 * `embedFile`、刷两张表、更新状态行 —— 那才是 Activity 的职责。
 *
 * 所以这里只搬前者：对话框怎么长、文案怎么说、按钮怎么排。保存动作
 * 通过 `onSave` / `onRelabel` 交回调用方。这样拆的好处不是"行数变少"，
 * 而是文案与分支（例如"全败看配置"那一类结论）可以脱离 Activity 阅读，
 * 而 Activity 里留下的是**它自己的状态机**。
 *
 * ⚠ 刻意**不**把 `Toast` 也搬进来：保存成功后的提示要与调用方实际做了
 *   什么一致（改标 / 清选择 / 重新加载），在这里再写一句就会与那边分岔。
 */
object ModelDialogs {

    /** 给用户看的类型名（对话框/提示/行标共用一套措辞，避免两处各叫一个名字）。 */
    fun roleLabel(role: ModelRole): String = when (role) {
        ModelRole.CHAT -> "对话 / 生成模型"
        ModelRole.EMBED -> "向量 / 嵌入模型"
        ModelRole.UNKNOWN -> "未分类"
    }

    /** 输入/修改模型别名；首导入默认 = 文件名去 .gguf（由 [ModelLibrary.aliasOf] 给）。 */
    fun promptAlias(ctx: Activity, f: File, isRename: Boolean, onSave: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(ModelLibrary.aliasOf(ctx, f.name))
            setSingleLine(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (isRename) "修改模型别名" else "给模型起个简短名字")
            .setMessage("客户端 /v1/models 和 model 参数将显示此名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val al = input.text.toString().trim()
                if (al.isNotEmpty()) onSave(al)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 列表行右侧的「类型」按钮：随时改标，**不改文件、不删文件**。
     *
     * 为什么需要一个显式入口而不只靠文件名：启发式只认得常见命名，而用户的
     * 模型可能叫 `model-q4.gguf`、`我的模型.gguf`。没有这个入口，认不出的文件
     * 会永远躺在两个列表里标着"未分类"，用户只能重命名文件来"修复"。
     */
    fun promptModelRole(
        ctx: Activity,
        f: File,
        cur: ModelRole,
        pal: ThemePalette,
        dp: (Int) -> Int,
        onSave: (ModelRole) -> Unit
    ) {
        val guess = ModelRole.guess(f.name)
        val rg = android.widget.RadioGroup(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val chatRb = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "对话 / 生成（装进对话槽）"
            textSize = UiText.BODY
            isChecked = cur == ModelRole.CHAT
        }
        val embedRb = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "向量 / 嵌入（装进嵌入槽）"
            textSize = UiText.BODY
            isChecked = cur == ModelRole.EMBED
        }
        val unknownRb = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "未分类（两个列表都显示，并标注未分类）"
            textSize = UiText.BODY
            isChecked = cur == ModelRole.UNKNOWN
        }
        rg.addView(chatRb)
        rg.addView(embedRb)
        rg.addView(unknownRb)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(ctx).apply {
            text = "文件：${f.name}"
            textSize = UiText.LABEL
            setTextColor(pal.textSecondary)
            setTextIsSelectable(true)
        })
        box.addView(TextView(ctx).apply {
            text = if (guess == ModelRole.UNKNOWN)
                "文件名里没有可识别的类型线索；请手动选择。"
            else "文件名看起来像${roleLabel(guess)}。"
            textSize = UiText.LABEL
            setTextColor(pal.textSecondary)
        })
        box.addView(rg)

        AlertDialog.Builder(ctx)
            .setTitle("修改模型类型")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val role = when (rg.checkedRadioButtonId) {
                    embedRb.id -> ModelRole.EMBED
                    unknownRb.id -> ModelRole.UNKNOWN
                    else -> ModelRole.CHAT
                }
                onSave(role)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 点错了槽时的拦截提示。**不是**一句"类型不对"就结束：用户可能真的标错了，
     * 所以直接给"改标并选用"的出口；如果没标错，也告诉他该去哪个页面加载。
     */
    fun showRoleMismatch(
        ctx: Activity,
        f: File,
        actual: ModelRole,
        onRelabel: (ModelRole) -> Unit
    ) {
        val wantEmbed = actual == ModelRole.EMBED
        val target = if (wantEmbed) ModelRole.CHAT else ModelRole.EMBED
        val targetLabel = roleLabel(target)
        val fromLabel = if (wantEmbed) "对话槽" else "嵌入槽"
        AlertDialog.Builder(ctx)
            .setTitle("类型不匹配：这是${roleLabel(actual)}")
            .setMessage(
                "「${f.name}」被标记为${roleLabel(actual)}，不能装进$fromLabel。\n\n" +
                    if (wantEmbed)
                        "如果类型标错了，点下面的按钮改标并选用；如果没标错，请到「知识库」页的嵌入模型列表加载它。"
                    else
                        "如果类型标错了，点下面的按钮改标并选用；如果没标错，请到「设置」页的模型库加载它。"
            )
            .setPositiveButton("改标为$targetLabel") { _, _ -> onRelabel(target) }
            .setNegativeButton("取消", null)
            .show()
    }
}
