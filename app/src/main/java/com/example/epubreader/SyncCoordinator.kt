package com.example.epubreader

/**
 * 轻量同步协调器：配置页（另一个 Activity 压栈在 MainActivity 之上）触发 MainActivity
 * 原地执行同步，并把运行状态/结果回传给配置页就地显示——避免配置页在同步时被关闭退回书架。
 *
 * host：MainActivity（onCreate 设、onDestroy 清），真正执行同步（持有 WebView/executor/WebDavSync）；
 * listener：配置页（onResume 注册、onPause 注销），接收状态更新。
 */
object SyncCoordinator {

    interface SyncListener {
        /** state: "running"（同步中）/ "done"（结束，message 为结果文案） */
        fun onSyncState(state: String, message: String)
    }

    var host: MainActivity? = null

    private var listener: SyncListener? = null

    fun setListener(l: SyncListener?) {
        listener = l
    }

    /** 配置页按钮调用：触发 MainActivity 原地执行同步 */
    fun requestSync(mode: WebDavSync.Mode) {
        host?.runSync(mode)
    }

    /** MainActivity 同步过程中/结束后回报状态（listener 为空时静默） */
    fun report(state: String, message: String) {
        listener?.onSyncState(state, message)
    }
}
