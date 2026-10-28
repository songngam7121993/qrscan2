package com.ngocdang.qrscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val NCOLS = 12          // hiển thị cột A..L
    private val CW = 100            // độ rộng ô (dp)
    private val RW = 44             // độ rộng cột số dòng (dp)

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private lateinit var etUrl: EditText
    private lateinit var etSheet: EditText
    private lateinit var etCol: EditText
    private lateinit var etRow: EditText
    private lateinit var tvStatus: TextView
    private lateinit var rv: RecyclerView

    private val grid = ArrayList<Array<String>>()
    private val adapter = RowAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val scanner = BarcodeScanning.getClient()
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)

    private var pendRow = -1; private var pendCol = -1
    private var okRow = -1; private var okCol = -1
    private var busy = false
    private var fetching = false
    private var paused = false
    private var lastCode = ""
    private var lastTime = 0L

    private val refresher = object : Runnable {
        override fun run() { fetchGrid(); handler.postDelayed(this, 5000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etUrl = findViewById(R.id.etUrl)
        etSheet = findViewById(R.id.etSheet)
        etCol = findViewById(R.id.etCol)
        etRow = findViewById(R.id.etRow)
        tvStatus = findViewById(R.id.tvStatus)
        rv = findViewById(R.id.rv)

        etUrl.setText(prefs.getString("url", ""))
        etSheet.setText(prefs.getString("sheet", ""))
        etCol.setText(prefs.getString("col", "A"))
        etRow.setText(prefs.getInt("row", 2).toString())

        // tiêu đề cột A..L
        val header = findViewById<LinearLayout>(R.id.header)
        header.setBackgroundColor(0xFFBBBBBB.toInt())
        for (i in 0..NCOLS) {
            header.addView(makeCell(i == 0).apply {
                text = if (i == 0) "#" else ('A' + (i - 1)).toString()
                setBackgroundColor(0xFFD0D0D0.toInt()); gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.layoutParams = (rv.layoutParams as LinearLayout.LayoutParams).apply { width = dp(RW + NCOLS * CW) }
        rv.adapter = adapter
        ensureRows(60)

        etRow.doAfterTextChanged { adapter.notifyDataSetChanged() }
        etCol.doAfterTextChanged { adapter.notifyDataSetChanged() }

        findViewById<Button>(R.id.btnPause).setOnClickListener { b ->
            paused = !paused
            (b as Button).text = if (paused) "Tiếp tục quét" else "Tạm dừng quét"
            tvStatus.text = if (paused) "Đã tạm dừng" else "Sẵn sàng quét"
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener { fetchGrid(true) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else tvStatus.text = "Cần cấp quyền camera để quét"
    }

    // ---------- Bảng ----------
    private fun makeCell(isRowNum: Boolean): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(if (isRowNum) RW else CW), dp(34)).apply { setMargins(1, 1, 1, 1) }
        setPadding(dp(4), 0, dp(4), 0)
        gravity = Gravity.CENTER_VERTICAL
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
    }

    private fun ensureRows(r: Int) {
        while (grid.size < r + 20) grid.add(Array(NCOLS) { "" })
    }

    private fun colIndex(): Int {
        val s = etCol.text.toString().trim().uppercase()
        return if (s.length == 1 && s[0] in 'A'..'Z') s[0] - 'A' else -1
    }

    private fun selectCell(r: Int, c: Int?) {
        etRow.setText(r.toString())
        if (c != null) etCol.setText(('A' + c).toString())
        prefs.edit().putString("col", etCol.text.toString()).putInt("row", r).apply()
    }

    private fun scrollTo(row: Int) {
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(maxOf(0, row - 3), 0)
    }

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(val box: LinearLayout, val cells: Array<TextView>) : RecyclerView.ViewHolder(box)

        override fun getItemCount() = grid.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val box = LinearLayout(p.context)
            box.orientation = LinearLayout.HORIZONTAL
            box.setBackgroundColor(0xFFBBBBBB.toInt())
            box.layoutParams = RecyclerView.LayoutParams(dp(RW + NCOLS * CW), dp(36))
            val cells = Array(NCOLS + 1) { i -> makeCell(i == 0).also { box.addView(it) } }
            return VH(box, cells)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val rNum = pos + 1
            val curRow = etRow.text.toString().toIntOrNull() ?: -1
            val curCol = colIndex()
            h.cells[0].apply {
                text = rNum.toString(); gravity = Gravity.CENTER
                setBackgroundColor(if (rNum == curRow) 0xFFFFEB3B.toInt() else 0xFFEEEEEE.toInt())
                setOnClickListener { selectCell(rNum, null) }
            }
            for (c in 0 until NCOLS) {
                h.cells[c + 1].apply {
                    text = grid[pos][c]
                    setBackgroundColor(when {
                        pos == pendRow && c == pendCol -> 0xFFFFB74D.toInt()   // đang ghi
                        pos == okRow && c == okCol -> 0xFFA5D6A7.toInt()       // vừa ghi xong
                        rNum == curRow && c == curCol -> 0xFFFFEB3B.toInt()    // ô sẽ ghi tiếp
                        rNum == curRow -> 0xFFFFF9C4.toInt()
                        else -> Color.WHITE
                    })
                    setOnClickListener { selectCell(rNum, c) }
                }
            }
        }
    }

    // ---------- Mạng ----------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(full: String): String {
        val c = URL(full).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000; c.instanceFollowRedirects = true
        return c.inputStream.bufferedReader().readText().trim()
    }

    private fun baseUrl(url: String) = url + (if (url.contains("?")) "&" else "?")

    private fun fetchGrid(manual: Boolean = false) {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty() || busy || fetching) return
        fetching = true
        val sheet = etSheet.text.toString().trim()
        val want = maxOf(60, (etRow.text.toString().toIntOrNull() ?: 1) + 30)
        Thread {
            var rows: ArrayList<Array<String>>? = null
            var err = ""
            try {
                val body = httpGet(baseUrl(url) + "action=read&sheet=${enc(sheet)}&rows=$want&cols=$NCOLS")
                if (!body.startsWith("{")) {
                    err = if (body.startsWith("ERR")) body else "Cần cập nhật Apps Script bản mới (có chức năng đọc bảng)"
                } else {
                    val arr = JSONObject(body).getJSONArray("values")
                    rows = ArrayList()
                    for (i in 0 until want) {
                        val line = Array(NCOLS) { "" }
                        if (i < arr.length()) {
                            val a = arr.getJSONArray(i)
                            for (j in 0 until minOf(NCOLS, a.length())) line[j] = a.getString(j)
                        }
                        rows.add(line)
                    }
                }
            } catch (e: Exception) { err = "Không tải được bảng: " + (e.message ?: "") }
            runOnUiThread {
                fetching = false
                if (rows != null) {
                    if (!busy) {
                        grid.clear(); grid.addAll(rows); ensureRows(want)
                        adapter.notifyDataSetChanged()
                        if (manual) tvStatus.text = "Đã tải bảng"
                    }
                } else if (manual || tvStatus.text.startsWith("Sẵn sàng")) tvStatus.text = err
            }
        }.start()
    }

    // ---------- Camera ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                val media = proxy.image
                if (media == null || paused || busy) {
                    proxy.close()
                } else {
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { list -> list.firstOrNull()?.rawValue?.let { onCode(it) } }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCode(code: String) {
        val now = System.currentTimeMillis()
        if (busy || (code == lastCode && now - lastTime < 2500)) return
        val url = etUrl.text.toString().trim()
        val row = etRow.text.toString().toIntOrNull()
        if (url.isEmpty() || row == null || row < 1) {
            tvStatus.text = "Nhập link Web App và số dòng hợp lệ"
            return
        }
        val col = etCol.text.toString().trim().ifEmpty { "A" }.uppercase()
        val sheet = etSheet.text.toString().trim()
        lastCode = code; lastTime = now; busy = true

        // hiện kết quả ngay lập tức trên bảng
        val ci = colIndex()
        ensureRows(row)
        var old = ""
        if (ci in 0 until NCOLS) { old = grid[row - 1][ci]; grid[row - 1][ci] = code }
        pendRow = row - 1; pendCol = ci
        adapter.notifyDataSetChanged()
        scrollTo(row)
        tvStatus.text = "Đang ghi $col$row: $code"

        Thread {
            var ok = false
            var msg = ""
            try {
                val body = httpGet(baseUrl(url) + "sheet=${enc(sheet)}&col=${enc(col)}&row=$row&value=${enc(code)}")
                ok = body.startsWith("OK"); msg = body.take(80)
            } catch (e: Exception) { msg = e.message ?: "Lỗi mạng" }
            runOnUiThread {
                if (ok) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    okRow = row - 1; okCol = ci; pendRow = -1
                    ensureRows(row + 1)
                    etRow.setText((row + 1).toString())
                    scrollTo(row + 1)
                    tvStatus.text = "Đã ghi $col$row. Dòng tiếp theo: ${row + 1}"
                    prefs.edit().putString("url", url).putString("sheet", sheet)
                        .putString("col", col).putInt("row", row + 1).apply()
                } else {
                    tone.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 300)
                    if (ci in 0 until NCOLS) grid[row - 1][ci] = old
                    pendRow = -1; lastCode = ""
                    tvStatus.text = "Lỗi, chưa ghi được: $msg"
                }
                busy = false
                adapter.notifyDataSetChanged()
            }
        }.start()
    }
}
