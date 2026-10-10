package fun.xwj.musicfree.qsign

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class SignService : Service() {
    private var webView: WebView? = null
    private var wm: WindowManager? = null
    private var serverSocket: ServerSocket? = null

    @Volatile private var ready = false
    private val waiters = ConcurrentHashMap<String, (String) -> Unit>()
    private var seq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1001, buildNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayWebView()
        startHttpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("qsign", "本机签名服务", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "qsign") else Notification.Builder(this)
        return b.setContentTitle("本机签名服务运行中")
            .setContentText("接口: http://127.0.0.1:8899/api/sign")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi).setOngoing(true).build()
    }

    /** 1x1 悬浮窗里的 WebView：窗口始终"可见"，Chromium 不会冻结它 */
    private fun createOverlayWebView() {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.allowFileAccessFromFileURLs = true
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.addJavascriptInterface(Bridge(), "QS")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                Log.i("qsign", "签名页加载完成")
            }
        }
        wv.loadUrl("file:///android_asset/qsign.html")
        val lp = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0; lp.y = 0
        try {
            wm?.addView(wv, lp)
            webView = wv
        } catch (e: Exception) {
            Log.e("qsign", "悬浮窗添加失败（没授权？）: ${e.message}")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun reply(id: String, payload: String) {
            waiters.remove(id)?.invoke(payload)
        }
    }

    private fun sign(url: String, headers: String, cb: (String) -> Unit) {
        val wv = webView
        if (wv == null || !ready) { cb("{\"error\":\"signer not ready\"}"); return }
        val id = "r" + (++seq)
        waiters[id] = cb
        val js = "window.__qsignCall(${JSONObject.quote(id)},${JSONObject.quote(url)},${JSONObject.quote(headers)})"
        wv.post { wv.evaluateJavascript(js, null) }
        Handler(Looper.getMainLooper()).postDelayed({
            waiters.remove(id)?.invoke("{\"error\":\"sign timeout\"}")
        }, 30000)
    }

    private fun startHttpServer() {
        Thread {
            try {
                val ss = ServerSocket(8899, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                Log.i("qsign", "HTTP 服务已启动 127.0.0.1:8899")
                while (!ss.isClosed) {
                    val s = ss.accept()
                    Thread { handle(s) }.start()
                }
            } catch (e: Exception) {
                Log.e("qsign", "HTTP 服务异常: ${e.message}")
            }
        }.start()
    }

    private fun handle(s: Socket) {
        try {
            val input = BufferedInputStream(s.getInputStream())
            val head = StringBuilder()
            var last4 = ""
            while (true) {
                val c = input.read()
                if (c < 0) return
                head.append(c.toChar())
                last4 = (last4 + c.toChar()).takeLast(4)
                if (last4 == "\r\n\r\n") break
                if (head.length > 65536) return
            }
            val lines = head.toString().split("\r\n")
            val first = lines.firstOrNull() ?: ""
            val parts = first.split(" ")
            val path = if (parts.size > 1) parts[1] else "/"
            var len = 0
            for (l in lines) if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(":").trim().toIntOrNull() ?: 0
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n <= 0) break
                off += n
            }
            val out: OutputStream = s.getOutputStream()
            if (path.startsWith("/health")) {
                send(out, 200, "{\"ok\":true,\"service\":\"qsign-local-app\",\"ready\":$ready}")
            } else if (path.startsWith("/api/sign")) {
                var answered = false
                val lock = Object()
                val payload = String(body, Charsets.UTF_8)
                val obj = try { JSONObject(payload) } catch (e: Exception) { null }
                if (obj == null || !obj.has("url")) { send(out, 400, "{\"error\":\"Missing required parameters: url and header\"}"); return }
                val hdrObj = obj.optJSONObject("header")
                val hdrStr = if (hdrObj != null) hdrObj.toString() else "{}"
                sign(obj.getString("url"), hdrStr) { result ->
                    synchronized(lock) {
                        if (!answered) {
                            answered = true
                            try { send(out, 200, result) } catch (e: Exception) {}
                            lock.notifyAll()
                        }
                    }
                }
                synchronized(lock) {
                    if (!answered) {
                        try { lock.wait(35000) } catch (e: Exception) {}
                    }
                }
            } else {
                send(out, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            Log.e("qsign", "请求处理失败: ${e.message}")
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    private fun send(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (e: Exception) {}
        try { webView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}
