package com.robomaster.control.ui

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.robomaster.control.databinding.ActivityVisionSettingsBinding

/**
 * 视觉功能设置界面（第三屏）。
 *
 * 2026-10 新增：车载视觉决策 APP 的各功能开关——
 * 图像预处理（去模糊/平滑/去辉光）、目标检测（高地靶/基地靶）、
 * 目标追踪（卡尔曼）、自动开火（弹道解算+摩擦轮速率）、导航（占位）。
 *
 * 防遮挡设计：
 * - 布局外层 ScrollView + applySystemBarsInsets()：根视图按实测系统栏 inset
 *   加 padding，状态栏/导航栏/刘海都不会遮住按钮；内容超出屏幕时可滚动。
 * - 开关行整行可点击（点击 label 也能切换 switch）。
 */
class VisionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionSettingsBinding
    private val vs by lazy { VisionSettings.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keepScreenOn()
        applySystemBarsInsets()   // 状态栏/导航栏避让，保证按钮可点

        loadSettings()
        setupControls()
    }

    /** 从 SharedPreferences 回填开关状态 */
    private fun loadSettings() {
        binding.swDeblur.isChecked = vs.deblurEnabled
        binding.swSmoothing.isChecked = vs.smoothingEnabled
        binding.swDeglare.isChecked = vs.deglareEnabled
        binding.swHighland.isChecked = vs.highlandTargetEnabled
        binding.swBase.isChecked = vs.baseTargetEnabled
        binding.swTracking.isChecked = vs.trackingEnabled
        binding.swAutoFire.isChecked = vs.autoFireEnabled
        binding.swNavigation.isChecked = vs.navigationEnabled
        binding.sbDetectEveryN.progress = vs.detectEveryNFrames - 1
        binding.tvDetectEveryN.text = vs.detectEveryNFrames.toString()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        // 开关：整行点击也可切换（点击 label 区域生效）
        binding.swDeblur.setOnCheckedChangeListener { _, c -> vs.deblurEnabled = c }
        binding.swSmoothing.setOnCheckedChangeListener { _, c -> vs.smoothingEnabled = c }
        binding.swDeglare.setOnCheckedChangeListener { _, c -> vs.deglareEnabled = c }
        binding.swHighland.setOnCheckedChangeListener { _, c -> vs.highlandTargetEnabled = c }
        binding.swBase.setOnCheckedChangeListener { _, c -> vs.baseTargetEnabled = c }
        binding.swTracking.setOnCheckedChangeListener { _, c -> vs.trackingEnabled = c }
        binding.swAutoFire.setOnCheckedChangeListener { _, c -> vs.autoFireEnabled = c }
        binding.swNavigation.setOnCheckedChangeListener { _, c -> vs.navigationEnabled = c }

        // 检测频率 SeekBar（1..30 帧）
        binding.sbDetectEveryN.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val n = progress + 1
                binding.tvDetectEveryN.text = n.toString()
                vs.detectEveryNFrames = n
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}
