package com.prabhu.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_VOICE = 101
private const val REQ_CAM = 102
private const val REQ_PICK = 103
private const val REQ_FILE = 104
private const val PERM_MIC = 201
private const val PERM_MEDIA = 202
private const val PERM_CAM_NOW = 203
private val cUserBg = 0xFF2A3860.toInt()
private val cDanger = 0x1FFF7B7B
private val cBg = Color.WHITE
private val cSurface = 0xFFF5F5F5.toInt()
private val cSurface2 = 0xFFF0F2F5.toInt()
private val cText = 0xFF111111.toInt()
private val cMute = 0xFF666666.toInt()
private val cLine = 0xFFE0E0E0.toInt()

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val dlgTheme = android.R.style.Theme_Material_Dialog_Alert

    private lateinit var root: FrameLayout
    private lateinit var chatPage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var settingsBody: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var msgList: LinearLayout
    private lateinit var input: EditText
    private lateinit var chip: TextView
    private lateinit var attachBar: LinearLayout
    private lateinit var attachImg: ImageView
    private lateinit var drawer: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var sendBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var searchBox: EditText

    private var chats = ArrayList<Chat>()
    private var cur: Chat? = null
    private var models = ArrayList<ModelCfg>()
    private val pendingAtts = ArrayList<Att>()
    private lateinit var attachLabel: TextView
    private var camUri: Uri? = null
    private var inVoice = false
    private var voiceFromWake = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var drawerOpen = false
    private var panelW = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        models = ArrayList(Store.models(this))
        chats = ArrayList(Store.loadChats(this))
        panelW = minOf((resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(330))
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        buildUi()
        showChat()
        renderChat()
        refreshChip()
        if (Store.bool(this, "wake", false) && micOk() && Settings.canDrawOverlays(this)) startWake()
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppState.visible = true
        wakeCmd("PAUSE")
    }

    override fun onStop() {
        super.onStop()
        AppState.visible = false
        if (!inVoice) wakeCmd("RESUME")
    }

    override fun onResume() {
        super.onResume()
        if (settingsPage.visibility == View.VISIBLE) refreshSettings()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        handleIntent(i)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            settingsPage.visibility == View.VISIBLE -> showChat()
            else -> super.onBackPressed()
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra("wake", false)) return
        val cmd = i.getStringExtra("cmd")?.trim().orEmpty()
        i.removeExtra("wake")
        i.removeExtra("cmd")
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        showChat()
        if (cmd.isNotEmpty()) send(cmd, emptyList(), true) else main.postDelayed({ startVoice(true) }, 350)
    }

    private fun wakeCmd(action: String) {
        if (!Store.bool(this, "wake", false)) return
        try { startService(Intent(this, AssistantService::class.java).setAction(action)) } catch (e: Exception) { }
    }

    private fun startWake() {
        try { startForegroundService(Intent(this, AssistantService::class.java)) } catch (e: Exception) { }
    }

    private fun stopWake() {
        try { startService(Intent(this, AssistantService::class.java).setAction("STOP")) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- small UI helpers

    private fun tv(s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun lpm(top: Int, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun iconBtn(t: String, f: () -> Unit) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        setTextColor(cText)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { f() }
    }

    private fun btn(label: String, filled: Boolean, f: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (filled) Color.WHITE else cText)
        background = if (filled) rounded(cAccent, 0, 12) else rounded(cSurface, cLine, 12)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { f() }
    }

    private fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setHintTextColor(cMute)
        setTextColor(cText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL)
        setSingleLine()
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(cSurface, cLine, 12)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun watch(f: () -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { f() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun avatar(sizeDp: Int) = TextView(this).apply {
        text = "P"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.45f)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF8AA4FF.toInt(), 0xFF5566F0.toInt())
        ).apply { shape = GradientDrawable.OVAL }
    }

    private fun bubbleBg(fill: Int, r: Int, small: Int): GradientDrawable {
        val a = dp(r).toFloat()
        val b = dp(small).toFloat()
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(a, a, a, a, b, b, a, a)
        }
    }

    private fun updateSendState() {
        val has = input.text.isNotBlank() || pendingAtts.isNotEmpty()
        sendBtn.visibility = if (has) View.VISIBLE else View.GONE
        micBtn.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun updateTitle() {
        val t = cur?.title
        titleView.text = if (t.isNullOrBlank()) "Prabhu" else t
    }

    private fun prefill(t: String) {
        input.setText(t)
        input.setSelection(input.text.length)
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun copyText(t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Prabhu", t))
        toast("Copied")
    }

    private fun shareText(t: String) {
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share"))
        } catch (e: Exception) { }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun camOk() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- build UI

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        chatPage = buildChatPage()
        settingsPage = buildSettingsPage()
        drawer = buildDrawer()
        root.addView(chatPage, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun buildChatPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        chip = tv("", 12f, cAccent, true).apply {
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { pickModel() }
        }
        titleView = tv("Prabhu", 16f, cText, true).apply {
            maxLines = 1
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(titleView, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        bar.addView(iconBtn("☰") { openDrawer() })
        bar.addView(mid, lp(0, WRAP, 1f))
        bar.addView(iconBtn("✎") { newChat() })
        page.addView(bar, lp(MATCH, WRAP))
        page.addView(View(this).apply { setBackgroundColor(cLine) }, lp(MATCH, 1))

        scroll = ScrollView(this).apply { isFillViewport = true }
        msgList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll.addView(msgList, FrameLayout.LayoutParams(MATCH, WRAP))
        page.addView(scroll, lp(MATCH, 0, 1f))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        attachBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(cSurface, cLine, 14)
        }
        attachImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        attachBar.addView(attachImg, lp(dp(56), dp(56)))
        attachLabel = tv("", 13f, cMute).apply { setPadding(dp(12), 0, 0, 0); maxLines = 3 }
        attachBar.addView(attachLabel, lp(0, WRAP, 1f))
        attachBar.addView(iconBtn("✕") { clearAttach() })
        box.addView(attachBar, lpm(0, 8))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = rounded(cSurface, cLine, 26)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "Message Prabhu"
            setHintTextColor(cMute)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        sendBtn = iconBtn("↑") { onSend() }.apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cAccent) }
        }
        micBtn = iconBtn("🎤") { startVoice(false) }
        row.addView(iconBtn("+") { attachMenu() })
        row.addView(input, lp(0, WRAP, 1f))
        row.addView(micBtn, lp(dp(44), dp(44)).apply { gravity = Gravity.BOTTOM })
        row.addView(sendBtn, lp(dp(40), dp(40)).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(2); leftMargin = dp(2) })
        sendBtn.visibility = View.GONE
        input.addTextChangedListener(watch { updateSendState() })
        box.addView(row, lp(MATCH, WRAP))
        page.addView(box, lp(MATCH, WRAP))
        return page
    }

    private fun buildDrawer(): FrameLayout {
        val d = FrameLayout(this).apply { visibility = View.GONE }
        val scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeDrawer() }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
            setPadding(dp(16), dp(28), dp(16), dp(16))
            translationX = -panelW.toFloat()
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(avatar(36), LinearLayout.LayoutParams(dp(36), dp(36)))
        head.addView(tv("Prabhu", 22f, cText, true).apply { setPadding(dp(10), 0, 0, 0) })
        panel.addView(head, lpm(0, 16))
        panel.addView(btn("+  New chat", true) { closeDrawer(); newChat() }, lpm(0, 12))
        searchBox = field("Search chats", "", false)
        searchBox.addTextChangedListener(watch { refreshDrawerList() })
        panel.addView(searchBox, lpm(0, 4))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(historyBox) }, lp(MATCH, 0, 1f))
        panel.addView(View(this).apply { setBackgroundColor(cLine) }, lp(MATCH, 1))
        panel.addView(
            tv("⚙   Settings", 16f, cText).apply {
                setPadding(dp(8), dp(16), dp(8), dp(8))
                setOnClickListener { closeDrawer(); showSettings() }
            }, lp(MATCH, WRAP)
        )
        d.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        d.addView(panel, FrameLayout.LayoutParams(panelW, MATCH))
        return d
    }

    private fun buildSettingsPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            visibility = View.GONE
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(iconBtn("←") { showChat() })
        bar.addView(tv("Settings", 18f, cText, true))
        page.addView(bar, lp(MATCH, WRAP))
        page.addView(View(this).apply { setBackgroundColor(cLine) }, lp(MATCH, 1))
        settingsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        page.addView(ScrollView(this).apply { addView(settingsBody) }, lp(MATCH, 0, 1f))
        return page
    }

    // ---------------------------------------------------------------- navigation

    private fun showChat() {
        settingsPage.visibility = View.GONE
        chatPage.visibility = View.VISIBLE
        refreshChip()
        val c = cur
        if (c == null || c.msgs.isEmpty()) renderChat()
    }

    private fun showSettings() {
        refreshSettings()
        chatPage.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
        hideKeyboard()
    }

    private fun openDrawer() {
        hideKeyboard()
        searchBox.setText("")
        refreshDrawerList()
        drawer.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(200).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        drawerOpen = false
        panel.animate().translationX(-panelW.toFloat()).setDuration(180)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun refreshDrawerList() {
        historyBox.removeAllViews()
        val q = if (::searchBox.isInitialized) searchBox.text.toString().trim().lowercase() else ""
        val list = chats.filter { q.isEmpty() || it.title.lowercase().contains(q) }
        if (list.isEmpty()) {
            historyBox.addView(tv(if (chats.isEmpty()) "No chats yet" else "No matches", 14f, cMute)
                .apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
            return
        }
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val today = cal.timeInMillis
        val day = 86_400_000L
        fun group(ch: Chat): String {
            val t = ch.id.toLongOrNull() ?: 0L
            return when {
                t >= today -> "Today"
                t >= today - day -> "Yesterday"
                t >= today - 7 * day -> "Previous 7 days"
                else -> "Older"
            }
        }
        var last = ""
        list.forEach { ch ->
            val g = group(ch)
            if (g != last) {
                last = g
                historyBox.addView(tv(g, 12f, cMute, true), lpm(14, 4))
            }
            val row = tv(ch.title, 15f, cText).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (ch === cur) background = rounded(cSurface2, 0, 10)
                setOnClickListener { cur = ch; renderChat(); closeDrawer() }
                setOnLongClickListener { chatMenu(ch); true }
            }
            historyBox.addView(row, lp(MATCH, WRAP))
        }
    }

    private fun chatMenu(ch: Chat) {
        AlertDialog.Builder(this, dlgTheme)
            .setTitle(ch.title.take(40))
            .setItems(arrayOf("Rename", "Delete")) { _, i -> if (i == 0) renameChat(ch) else deleteChat(ch) }
            .show()
    }

    private fun renameChat(ch: Chat) {
        val f = field("Chat name", ch.title, false)
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        AlertDialog.Builder(this, dlgTheme)
            .setTitle("Rename chat")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val t = f.text.toString().trim()
                if (t.isNotEmpty()) {
                    ch.title = t
                    Store.saveChats(this, chats)
                    refreshDrawerList()
                    updateTitle()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteChat(ch: Chat) {
        AlertDialog.Builder(this, dlgTheme)
            .setTitle("Delete this chat?")
            .setPositiveButton("Delete") { _, _ ->
                chats.remove(ch)
                if (cur === ch) { cur = null; renderChat() }
                Store.saveChats(this, chats)
                refreshDrawerList()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newChat() {
        cur = null
        clearAttach()
        input.setText("")
        renderChat()
        showChat()
    }

    private fun pickModel() {
        models = ArrayList(Store.models(this))
        if (models.isEmpty()) { showSettings(); return }
        val act = Store.active(this)
        val names = models.mapIndexed { i, m -> (if (i == act) "✓  " else "      ") + m.name } + "Manage models…"
        AlertDialog.Builder(this, dlgTheme)
            .setTitle("Model")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == models.size) showSettings() else { Store.setActive(this, i); refreshChip() }
            }.show()
    }

    private fun refreshChip() {
        models = ArrayList(Store.models(this))
        val n = models.getOrNull(Store.active(this))?.name ?: "Add a model"
        chip.text = "$n  ▾"
    }

    // ---------------------------------------------------------------- chat rendering

    private fun renderChat() {
        msgList.removeAllViews()
        updateTitle()
        val c = cur
        if (c == null || c.msgs.isEmpty()) {
            msgList.addView(emptyState())
            return
        }
        val lastIdx = c.msgs.lastIndex
        c.msgs.forEachIndexed { i, m -> addMsgView(m, i == lastIdx) }
        scrollDown()
    }

    private fun emptyState(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(avatar(64), LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(48); bottomMargin = dp(16) })
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = when {
            h < 5 -> "Hello, night owl"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            h < 21 -> "Good evening"
            else -> "Hello, night owl"
        }
        box.addView(tv(greet, 26f, cText, true).apply { gravity = Gravity.CENTER }, lpm(0, 4))
        box.addView(tv("How can I help you today?", 15f, cMute).apply { gravity = Gravity.CENTER }, lpm(0, 24))

        if (Store.models(this).none { it.key.isNotBlank() }) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cSurface, cAccent, 16)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(tv("No API key yet", 15f, cText, true))
            card.addView(tv("Add a Gemini, Groq, Claude, OpenAI or any other key to start chatting.", 13f, cMute), lpm(4, 12))
            card.addView(btn("Add API key", true) { showSettings() })
            box.addView(card, lpm(0, 20))
        }

        val sugs = listOf<Pair<String, () -> Unit>>(
            "📷  Ask about a photo" to { attachMenu() },
            "📄  Summarize a PDF" to { attachMenu() },
            "✍️  Write an email" to { prefill("Write a short, polite email about ") },
            "🌐  Translate to Telugu" to { prefill("Translate to Telugu: ") }
        )
        var i = 0
        while (i < sugs.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in i..minOf(i + 1, sugs.size - 1)) {
                val sg = sugs[j]
                r.addView(tv(sg.first, 14f, cText).apply {
                    background = rounded(cSurface, cLine, 16)
                    setPadding(dp(14), dp(16), dp(14), dp(16))
                    setOnClickListener { sg.second() }
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            }
            box.addView(r, lpm(0, 8))
            i += 2
        }
        return box
    }

    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun addMsgView(m: Msg, last: Boolean = false): View {
        val v = if (m.role == "user") userView(m) else assistantView(m.model, m.text, false, last)
        msgList.addView(v)
        return v
    }

    private fun userView(m: Msg): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) }
        }
        val th = m.thumb
        if (th != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(th)
                adjustViewBounds = true
                maxWidth = dp(220)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            box.addView(iv, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6); gravity = Gravity.END })
        }
        if (m.files.isNotBlank()) {
            box.addView(tv("📎 " + m.files, 12f, cMute),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; bottomMargin = dp(4) })
        }
        val bubble = tv(m.text, 16f, cText).apply {
            background = bubbleBg(cUserBg, 20, 6)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setTextIsSelectable(true)
        }
        box.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        return box
    }

    private fun assistantView(model: String, text: String, thinking: Boolean = false, last: Boolean = false): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(avatar(22), LinearLayout.LayoutParams(dp(22), dp(22)))
        head.addView(tv(model.ifBlank { "Prabhu" }, 12f, cMute, true).apply { setPadding(dp(8), 0, 0, 0) })
        box.addView(head, lpm(0, 8))
        if (thinking) {
            val dots = tv("●  ●  ●", 14f, cAccent)
            val anim = android.animation.ObjectAnimator.ofFloat(dots, "alpha", 0.2f, 1f).apply {
                duration = 650
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
            dots.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { if (!anim.isStarted) anim.start() }
                override fun onViewDetachedFromWindow(v: View) { anim.cancel() }
            })
            box.addView(dots, lp(WRAP, WRAP))
            return box
        }
        val body = tv(Md.render(text, cSurface2), 16f, cText).apply {
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }
        box.addView(body, lp(MATCH, WRAP))
        box.addView(actionRow(text, last), lp(MATCH, WRAP))
        return box
    }

    private fun actionRow(text: String, last: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(label: String, f: () -> Unit) {
            row.addView(tv(label, 12f, cMute).apply {
                setPadding(0, dp(10), dp(18), dp(4))
                setOnClickListener { f() }
            })
        }
        act("⧉ Copy") { copyText(text) }
        act("🔊 Listen") { if (tts?.isSpeaking == true) tts?.stop() else speak(text) }
        act("⇪ Share") { shareText(text) }
        if (last) act("↻ Retry") { regenerate() }
        return row
    }

    private fun addError(text: String, chat: Chat, spoken: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cDanger, 0, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(tv(text, 14f, cErr))
        card.addView(tv("↻  Try again", 14f, cAccent, true).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                msgList.removeView(card)
                if (!busy) runAsk(chat, spoken)
            }
        })
        msgList.addView(card, lpm(0, 16))
        scrollDown()
    }

    // ---------------------------------------------------------------- sending

    private fun onSend() {
        if (busy) return
        val t = input.text.toString().trim()
        if (t.isEmpty() && pendingAtts.isEmpty()) return
        send(t, pendingAtts, false)
    }

    private fun send(text: String, atts: List<Att>, spoken: Boolean) {
        if (busy) return
        models = ArrayList(Store.models(this))
        if (models.none { it.key.isNotBlank() }) {
            toast("Add an API key first")
            showSettings()
            return
        }
        val body = if (text.isNotBlank()) text
            else if (atts.any { it.kind != "image" }) "Summarize this file." else "What is in this image?"
        val chat: Chat = cur ?: Chat(System.currentTimeMillis().toString(), "New chat").also {
            chats.add(0, it); cur = it
        }
        if (chat.msgs.isEmpty()) {
            chat.title = body.take(40)
            msgList.removeAllViews()
        }
        updateTitle()
        val um = Msg("user", body, "", atts.joinToString(", ") { it.name })
        um.thumb = atts.firstOrNull { it.thumb != null }?.thumb
        um.atts = ArrayList(atts)
        chat.msgs.add(um)
        Store.saveChats(this, chats)
        addMsgView(um)
        input.setText("")
        clearAttach()

        runAsk(chat, spoken)
    }

    private fun runAsk(chat: Chat, spoken: Boolean) {
        models = ArrayList(Store.models(this))
        val active = Store.active(this)
        val name = models.getOrNull(active)?.name ?: "Prabhu"
        val thinking = assistantView(name, "", true)
        msgList.addView(thinking)
        scrollDown()
        busy = true
        input.hint = "Prabhu is thinking…"
        tts?.stop()

        val fb = Store.bool(this, "fallback", true)
        val snapshot = ArrayList(chat.msgs)
        val snapModels = ArrayList(models)
        Thread {
            val r = Ai.ask(snapModels, active, fb, snapshot, spoken)
            runOnUiThread { onReply(chat, thinking, r, spoken) }
        }.start()
    }

    private fun onReply(chat: Chat, thinking: View, r: Ai.Result, spoken: Boolean) {
        busy = false
        input.hint = "Message Prabhu"
        msgList.removeView(thinking)
        if (r.ok) {
            chat.msgs.add(Msg("assistant", r.text, r.used))
            Store.saveChats(this, chats)
            if (cur === chat) renderChat()
            if (spoken || Store.bool(this, "speak", false)) speak(r.text)
        } else if (cur === chat) {
            addError(r.text, chat, spoken)
        }
    }

    private fun regenerate() {
        val c = cur ?: return
        if (busy) return
        if (c.msgs.lastOrNull()?.role == "assistant") c.msgs.removeAt(c.msgs.lastIndex)
        if (c.msgs.lastOrNull()?.role != "user") return
        Store.saveChats(this, chats)
        renderChat()
        runAsk(c, false)
    }

    private fun speak(text: String) {
        val t = tts
        if (!ttsReady || t == null) return
        t.language = Locale.forLanguageTag(Store.lang(this))
        t.speak(text.replace(Regex("[*#`_]"), ""), TextToSpeech.QUEUE_FLUSH, null, "p")
    }

    // ---------------------------------------------------------------- voice, camera, photos

    private fun startVoice(fromWake: Boolean) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.lang(this@MainActivity))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        try {
            inVoice = true
            voiceFromWake = fromWake
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            inVoice = false
            toast("Voice input is not available on this phone")
        }
    }

    private fun attachMenu() {
        AlertDialog.Builder(this, dlgTheme)
            .setItems(arrayOf("Take a photo", "Choose a photo", "Choose a file (PDF, Word, text)")) { _, i ->
                if (i == 0) {
                    if (camOk()) launchCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA), PERM_CAM_NOW)
                } else if (i == 2) {
                    val f = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/pdf", "text/*", "application/json", "application/octet-stream",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    startActivityForResult(f, REQ_FILE)
                } else {
                    val p = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(p, REQ_PICK)
                }
            }.show()
    }

    private fun launchCamera() {
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "prabhu_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Prabhu")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) { toast("Couldn't open the camera"); return }
            camUri = uri
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(i, REQ_CAM)
        } catch (e: Exception) {
            toast("Camera is not available")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_VOICE -> {
                inVoice = false
                val fromWake = voiceFromWake
                voiceFromWake = false
                if (res == RESULT_OK) {
                    val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        if (fromWake) send(t, emptyList(), true)
                        else {
                            val old = input.text.toString()
                            input.setText(if (old.isBlank()) t else "$old $t")
                            input.setSelection(input.text.length)
                        }
                    }
                }
            }
            REQ_CAM -> {
                val u = camUri
                camUri = null
                if (u != null) {
                    if (res == RESULT_OK) attachUri(u)
                    else try { contentResolver.delete(u, null, null) } catch (e: Exception) { }
                }
            }
            REQ_PICK, REQ_FILE -> if (res == RESULT_OK) data?.data?.let { attachUri(it) }
        }
    }

    private fun attachUri(uri: Uri) {
        Thread {
            val a = readAtt(uri)
            runOnUiThread {
                if (a == null) toast("Couldn't read that file. Supported: images, PDF, Word (.docx), text")
                else { pendingAtts.add(a); refreshAttachBar() }
            }
        }.start()
    }

    private fun refreshAttachBar() {
        updateSendState()
        if (pendingAtts.isEmpty()) { attachBar.visibility = View.GONE; return }
        val th = pendingAtts.firstOrNull { it.thumb != null }?.thumb
        if (th != null) { attachImg.setImageBitmap(th); attachImg.visibility = View.VISIBLE }
        else attachImg.visibility = View.GONE
        attachLabel.text = pendingAtts.joinToString("\n") { "📎 " + it.name }
        attachBar.visibility = View.VISIBLE
    }

    private fun clearAttach() {
        pendingAtts.clear()
        attachBar.visibility = View.GONE
        updateSendState()
    }

    private fun displayName(uri: Uri): String {
        var n = ""
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0) ?: ""
            }
        } catch (e: Exception) { }
        return n.ifBlank { uri.lastPathSegment ?: "file" }
    }

    private fun readLimited(uri: Uri, max: Int): ByteArray? {
        contentResolver.openInputStream(uri)?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            return out.toByteArray()
        }
        return null
    }

    private fun readAtt(uri: Uri): Att? {
        val name = displayName(uri)
        val lower = name.lowercase()
        val mime = contentResolver.getType(uri) ?: ""
        val textExt = listOf(".txt", ".md", ".csv", ".json", ".xml", ".html", ".log", ".py", ".kt", ".java", ".js", ".c", ".cpp", ".yml", ".yaml")
        return try {
            when {
                mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ->
                    decode(uri)?.let { r -> Att("image", "image/jpeg", r.first, name).also { it.thumb = r.second } }
                mime == "application/pdf" || lower.endsWith(".pdf") -> readPdf(uri, name)
                lower.endsWith(".docx") -> readDocx(uri, name)
                mime.startsWith("text/") || mime == "application/json" || textExt.any { lower.endsWith(it) } -> {
                    val bytes = readLimited(uri, 3_000_000) ?: return null
                    Att("text", "text/plain", String(bytes, Charsets.UTF_8).take(80000), name)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readPdf(uri: Uri, name: String): Att? {
        val bytes = readLimited(uri, 15 * 1024 * 1024) ?: return null
        val a = Att("pdf", "application/pdf", Base64.encodeToString(bytes, Base64.NO_WRAP), name)
        val pages = ArrayList<String>()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val r = PdfRenderer(pfd)
                val n = minOf(r.pageCount, 6)
                for (i in 0 until n) {
                    val p = r.openPage(i)
                    val w = 1000
                    val h = maxOf(1, w * p.height / maxOf(1, p.width))
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    if (i == 0) a.thumb = Bitmap.createScaledBitmap(bmp, 170, maxOf(1, 170 * h / w), true)
                    val o = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
                    pages.add(Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP))
                }
                r.close()
            }
        } catch (e: Exception) { }
        a.pages = pages
        return a
    }

    private fun readDocx(uri: Uri, name: String): Att? {
        val input = contentResolver.openInputStream(uri) ?: return null
        var xml = ""
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") { xml = String(z.readBytes(), Charsets.UTF_8); break }
                e = z.nextEntry
            }
        }
        if (xml.isEmpty()) return null
        val text = xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim().take(80000)
        return Att("text", "text/plain", text, name)
    }

    private fun decode(uri: Uri): Pair<String, Bitmap>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val deg = contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                }
            } ?: 0
            if (deg != 0) {
                val m = Matrix().apply { postRotate(deg.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val th = 240
            val tw = maxOf(1, bmp.width * th / maxOf(1, bmp.height))
            Pair(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), Bitmap.createScaledBitmap(bmp, tw, th, true))
        } catch (e: Exception) {
            null
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERM_CAM_NOW && camOk()) launchCamera()
        if (settingsPage.visibility == View.VISIBLE) refreshSettings()
    }

    // ---------------------------------------------------------------- settings

    private fun section(title: String) {
        settingsBody.addView(tv(title, 13f, cMute, true), lpm(24, 8))
    }

    private fun refreshSettings() {
        val b = settingsBody
        b.removeAllViews()
        models = ArrayList(Store.models(this))
        val active = Store.active(this)

        section("Models & API keys")
        models.forEachIndexed { i, m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cSurface, if (i == active) cAccent else cLine, 14)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { editModel(i, false, m.copy()) }
            }
            card.addView(tv(m.name + if (i == active) "   • in use" else "", 16f, cText, true))
            val typeName = Presets.typeLabels.firstOrNull { it.first == m.type }?.second ?: m.type
            val key = if (m.key.isBlank()) "no key" else "key ••••" + m.key.takeLast(4)
            card.addView(tv("$typeName · ${m.modelId.ifBlank { "no model ID" }} · $key", 12f, cMute), lpm(2))
            b.addView(card, lpm(0, 8))
        }
        b.addView(btn("+  Add model / API", false) { addModelDialog() }, lpm(4, 0))
        b.addView(switchRow(
            "Auto-switch on failure",
            "If a model hits its limit or fails, try the next one.",
            Store.bool(this, "fallback", true)
        ) { Store.setBool(this, "fallback", it) }, lpm(8))

        section("Voice")
        val ln = Prefs2.langName(Store.lang(this))
        b.addView(btn("Language: $ln  (tap to change)", false) {
            Store.setLang(this, Prefs2.nextLang(Store.lang(this)))
            refreshSettings()
        }, lpm(0, 4))
        b.addView(switchRow("Read replies aloud", "Prabhu speaks every answer.", Store.bool(this, "speak", false)) {
            Store.setBool(this, "speak", it)
        }, lpm(4))
        b.addView(switchRow(
            "Hey Prabhu",
            "Say \"Hey Prabhu\" and the app opens, ready to listen.",
            Store.bool(this, "wake", false)
        ) { on ->
            if (on) {
                if (!micOk() || !Settings.canDrawOverlays(this)) {
                    toast("Allow Microphone and Display over other apps first")
                    Store.setBool(this, "wake", false)
                    refreshSettings()
                } else {
                    Store.setBool(this, "wake", true)
                    startWake()
                }
            } else {
                Store.setBool(this, "wake", false)
                stopWake()
            }
        }, lpm(4))

        section("Permissions")
        b.addView(permRow("Microphone", micOk()) {
            val p = ArrayList<String>()
            p.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), PERM_MIC)
        }, lpm(0, 6))
        b.addView(permRow("Camera & photos", camOk()) {
            val p = ArrayList<String>()
            p.add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.READ_MEDIA_IMAGES)
            else p.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < 29) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestPermissions(p.toTypedArray(), PERM_MEDIA)
        }, lpm(0, 6))
        b.addView(permRow("Display over other apps (to open on \"Hey Prabhu\")", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, lpm(0, 6))
        b.addView(permRow("Run in background (battery)", false, "Open") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
        }, lpm(0, 6))

        section("About")
        b.addView(tv("API keys stay on this phone and are sent only to the AI provider you choose.", 13f, cMute), lpm(0))
    }

    private fun switchRow(label: String, sub: String, on: Boolean, f: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface, cLine, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 15f, cText))
        if (sub.isNotEmpty()) col.addView(tv(sub, 12f, cMute))
        val sw = Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, v -> f(v) }
        }
        r.addView(col, lp(0, WRAP, 1f))
        r.addView(sw)
        return r
    }

    private fun permRow(label: String, ok: Boolean, action: String = "Allow", f: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface, cLine, 14)
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 15f, cText))
        if (action == "Allow") col.addView(tv(if (ok) "Allowed" else "Not allowed yet", 12f, if (ok) cMute else cErr))
        r.addView(col, lp(0, WRAP, 1f))
        if (!ok || action != "Allow") {
            r.addView(btn(action, true) { f() }.apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        }
        return r
    }

    private fun addModelDialog() {
        AlertDialog.Builder(this, dlgTheme)
            .setTitle("Add model / API")
            .setItems(Presets.labels.toTypedArray()) { _, i -> editModel(-1, true, Presets.items[i].copy()) }
            .show()
    }

    private fun editModel(idx: Int, isNew: Boolean, cfg: ModelCfg) {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val fName = field("Name shown in chat (e.g. Claude Sonnet 5.5)", cfg.name, false)
        var type = cfg.type
        val typeBtn = btn("", false) {}
        fun setTypeText() {
            typeBtn.text = "Type: " + (Presets.typeLabels.firstOrNull { it.first == type }?.second ?: type) + "  (tap)"
        }
        setTypeText()
        typeBtn.setOnClickListener {
            val order = Presets.typeLabels.map { it.first }
            type = order[(order.indexOf(type) + 1) % order.size]
            setTypeText()
        }
        val fKey = field("API key", cfg.key, true)
        val fModel = field("Model ID (e.g. claude-sonnet-5-5)", cfg.modelId, false)
        val fUrl = field("Base URL (only for OpenAI-compatible)", cfg.baseUrl, false)
        l.addView(fName, lpm(0, 8)); l.addView(typeBtn, lpm(0, 8)); l.addView(fKey, lpm(0, 8))
        l.addView(fModel, lpm(0, 8)); l.addView(fUrl, lpm(0, 8))

        val dlg = AlertDialog.Builder(this, dlgTheme)
            .setTitle(if (isNew) "Add model" else "Edit model")
            .setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Save") { _, _ ->
                val mid = fModel.text.toString().trim()
                val nm = fName.text.toString().trim().ifEmpty { mid.ifEmpty { "My AI" } }
                val out = ModelCfg(nm, type, fKey.text.toString().trim(), mid, fUrl.text.toString().trim())
                if (isNew) models.add(out) else if (idx in models.indices) models[idx] = out
                Store.saveModels(this, models)
                if (isNew && models.size == 1) Store.setActive(this, 0)
                refreshSettings()
                refreshChip()
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) dlg.setNeutralButton("Delete") { _, _ ->
            if (idx in models.indices) models.removeAt(idx)
            Store.saveModels(this, models)
            if (Store.active(this) >= models.size) Store.setActive(this, 0)
            refreshSettings()
            refreshChip()
        }
        dlg.show()
    }
}

object Prefs2 {
    private val langs = listOf("te-IN" to "Telugu", "en-IN" to "English", "hi-IN" to "Hindi")
    fun langName(code: String) = langs.firstOrNull { it.first == code }?.second ?: code
    fun nextLang(code: String): String {
        val i = langs.indexOfFirst { it.first == code }
        return langs[(i + 1) % langs.size].first
    }
}
