package com.example.epubreader

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.epubreader.databinding.ActivityWebdavSettingsBinding
import java.util.UUID
import java.util.concurrent.Executors

/**
 * WebDAV 配置页（原生）：支持多份服务器配置，点选单选启用其一。
 *
 * 覆盖上传/云端下载在此页**原地执行**（不关闭页面）：经 [SyncCoordinator] 触发
 * MainActivity 同步，状态行就地显示「同步中… → 结果」，避免退回书架、保证结果可见。
 *
 * 离开方式仅「返回书架」；配置有改动时以 [ACTION_SAVE] 返回，MainActivity 按启用项的
 * 自动同步开关决定是否立即自动同步。
 */
class WebDavSettingsActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SAVE = 1001
    }

    private lateinit var binding: ActivityWebdavSettingsBinding
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sync: WebDavSync

    /** 表单当前对应的配置 id；null = 新建配置 */
    private var currentId: String? = null

    /** 当前表单对应配置是否启用（保存时决定互斥） */
    private var currentEnabled = false

    /** 配置是否有结构改动（新建/启用/删除/保存），返回书架时决定 resultCode */
    private var dirty = false

    private var syncing = false

    /** 程序化改 radio 时抑制监听回调：clearCheck/重建列表会触发 checkedChange，
     *  若不去重会立即把表单弹回当前启用配置、currentId 被改回，导致保存覆盖错配置。 */
    private var updatingRadios = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebdavSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sync = WebDavSync(this, EpubStore(this))

        binding.btnNew.setOnClickListener { startNewConfig() }
        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnUpload.setOnClickListener { requestSync(WebDavSync.Mode.UPLOAD_ALL) }
        binding.btnDownload.setOnClickListener { requestSync(WebDavSync.Mode.DOWNLOAD_ALL) }
        binding.btnBack.setOnClickListener { leaveWithResult() }
        // 系统返回键与「返回书架」按钮同逻辑：配置有改动也返回 ACTION_SAVE，
        // 否则 MainActivity 收不到保存信号，书架上的配置名不会刷新（保持旧名）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leaveWithResult()
            }
        })
        binding.rgConfigs.setOnCheckedChangeListener { _, checkedId ->
            onConfigSelected(checkedId)
        }

        rebuildConfigList()
    }

    override fun onResume() {
        super.onResume()
        // 注册为同步状态接收者：MainActivity 同步中/完成后回报这里就地显示
        SyncCoordinator.setListener(object : SyncCoordinator.SyncListener {
            override fun onSyncState(state: String, message: String) {
                runOnUiThread { updateStatus(state, message) }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        SyncCoordinator.setListener(null)
    }

    // ---------------- 配置列表 ----------------

    private fun rebuildConfigList() {
        updatingRadios = true
        binding.rgConfigs.removeAllViews()
        val configs = sync.getConfigs()
        binding.tvEmptyConfig.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        for (cfg in configs) {
            val rb = RadioButton(this)
            rb.text = cfg.name
            rb.id = View.generateViewId()
            rb.tag = cfg.id
            rb.isChecked = cfg.enabled
            // 已启用项 radio 已勾选，再点它 RadioGroup 不会触发 checkedChange（选择没变），
            // 用独立点击事件保证「重选已启用配置」也能把它加载进表单修改
            rb.setOnClickListener {
                val c = sync.getConfigs().firstOrNull { it.id == rb.tag as String }
                if (c != null) selectConfig(c.copy(enabled = true))
            }
            binding.rgConfigs.addView(rb)
        }
        if (configs.isEmpty()) {
            startNewConfig()
        } else {
            val active = configs.firstOrNull { it.enabled } ?: configs.first()
            selectConfig(active)
        }
        updatingRadios = false
    }

    private fun onConfigSelected(checkedId: Int) {
        if (updatingRadios) return
        val rb = findViewById<RadioButton>(checkedId) ?: return
        val cfg = sync.getConfigs().firstOrNull { it.id == rb.tag as String } ?: return
        if (!cfg.enabled) {
            sync.upsertConfig(cfg.copy(enabled = true)) // 互斥：启用本条、其它禁用
            dirty = true
        }
        selectConfig(cfg.copy(enabled = true))
    }

    private fun selectConfig(cfg: WebDavConfig) {
        currentId = cfg.id
        currentEnabled = cfg.enabled
        binding.etName.setText(cfg.name)
        binding.etUrl.setText(cfg.url)
        binding.etUser.setText(cfg.user)
        binding.etPass.setText(cfg.pass)
        binding.swAuto.isChecked = cfg.auto
        binding.tvStatus.text = if (cfg.enabled) "已启用「${cfg.name}」，同步使用这份配置" else "已选择「${cfg.name}」（未启用）"
    }

    private fun startNewConfig() {
        updatingRadios = true
        currentId = null
        currentEnabled = sync.getConfigs().isEmpty() // 第一份配置默认启用
        binding.rgConfigs.clearCheck()
        binding.etName.setText("")
        binding.etUrl.setText("")
        binding.etUser.setText("")
        binding.etPass.setText("")
        binding.swAuto.isChecked = true
        binding.tvStatus.text = "填写下方信息并保存，新建一条配置"
        updatingRadios = false
    }

    // ---------------- 表单操作 ----------------

    private fun testConnection() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请先填写服务器地址")
            return
        }
        if (syncing) return
        binding.btnTest.isEnabled = false
        binding.tvStatus.text = "正在测试连接…"
        val cfg = formValues()
        executor.execute {
            val result = sync.testConnection(cfg)
            runOnUiThread {
                binding.btnTest.isEnabled = true
                val msg = if (result == null) "✅ 连接成功，云端目录已就绪" else "❌ $result"
                binding.tvStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestSync(mode: WebDavSync.Mode) {
        if (!sync.isConfigured()) {
            binding.tvStatus.text = "请先点选启用一份配置"
            toast("请先点选启用一份配置")
            return
        }
        SyncCoordinator.requestSync(mode)
    }

    private fun saveCurrent() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请填写服务器地址")
            return
        }
        val cfg = formValues().copy(enabled = currentEnabled)
        sync.upsertConfig(cfg)
        dirty = true
        toast("已保存「${cfg.name}」")
        rebuildConfigList()
        selectAndCheck(cfg)
    }

    private fun deleteCurrent() {
        val id = currentId ?: run {
            toast("没有可删除的配置")
            return
        }
        sync.deleteConfig(id)
        dirty = true
        toast("已删除该配置")
        rebuildConfigList()
    }

    /** 离开配置页：配置有改动返回 ACTION_SAVE（MainActivity 据此刷新配置名/自动同步），否则 RESULT_CANCELED */
    private fun leaveWithResult() {
        setResult(if (dirty) ACTION_SAVE else RESULT_CANCELED)
        finish()
    }

    /** 保存后把该配置加载进表单；单选勾选始终指向「当前启用」的那份（新保存的若启用就勾它，否则保持原启用项） */
    private fun selectAndCheck(cfg: WebDavConfig) {
        selectConfig(cfg)
        updatingRadios = true
        binding.rgConfigs.clearCheck()
        val active = sync.getActiveConfig() ?: cfg
        for (i in 0 until binding.rgConfigs.childCount) {
            val child = binding.rgConfigs.getChildAt(i)
            if (child is RadioButton && child.tag == active.id) {
                child.isChecked = true
            }
        }
        updatingRadios = false
    }

    /** 读取表单字段组装配置（enabled 由调用方决定） */
    private fun formValues(): WebDavConfig {
        var name = binding.etName.text.toString().trim()
        if (name.isEmpty()) name = "我的配置"
        var url = binding.etUrl.text.toString().trim()
        if (url.isNotEmpty() && !url.endsWith("/")) url += "/"
        return WebDavConfig(
            id = currentId ?: UUID.randomUUID().toString(),
            name = name,
            url = url,
            user = binding.etUser.text.toString().trim(),
            pass = binding.etPass.text.toString(),
            auto = binding.swAuto.isChecked,
            enabled = false,
        )
    }

    // ---------------- 状态 ----------------

    /** state: "running"（同步中）/ "done"（结束）。运行中禁用同步/测试按钮，结束后恢复。 */
    private fun updateStatus(state: String, message: String) {
        syncing = state == "running"
        binding.btnUpload.isEnabled = !syncing
        binding.btnDownload.isEnabled = !syncing
        binding.btnTest.isEnabled = !syncing
        binding.tvStatus.text = message
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
