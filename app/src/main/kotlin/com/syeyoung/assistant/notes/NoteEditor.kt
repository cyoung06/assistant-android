package com.syeyoung.assistant.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import com.syeyoung.assistant.core.NoteStyle
import com.syeyoung.assistant.core.Span
import com.syeyoung.assistant.core.markdownSpans
import com.syeyoung.assistant.core.noteStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * A Markdown note being written, natively: the text in an [EditText] styled as the web editor's
 * live preview (see [LivePreview]), saved by [NoteDocument]. [onStatus] hears how saving is going;
 * [onLink] opens a followed link (a web address, or with its drive path, a drive file).
 */
class NoteEditor(
    context: Context,
    val path: String,
    private val onStatus: (state: String, message: String?) -> Unit,
    private val onLink: (href: String, resolvedPath: String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val document = NoteDocument(context, path, { view.text.toString() }, onStatus)
    private var style: NoteStyle = noteStyle(false)
    private var fonts: NoteCore.Fonts? = null
    private var preview: LivePreview? = null
    /** The structure of the text as it is (worked out again when it changes). */
    private var spans: List<Span> = emptyList()
    private var source = false
    /** Setting text that wasn't typed (opening, another version): not an edit. */
    private var loading = true

    @SuppressLint("ClickableViewAccessibility")
    val view: EditText = object : EditText(context) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            restyle(structure = false)
        }

        override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect)
            restyle(structure = false)
            if (!focused) scope.launch { document.save() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Room below the last line, as on the web (the text can scroll up past it).
            setPadding(paddingLeft, paddingTop, paddingRight, h * 2 / 5)
            if (w != oldw) restyle(structure = false)
        }
    }.apply {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)
        isEnabled = false
        hint = "Write…"
    }

    init {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                restyle(structure = true)
                if (!loading) document.edited()
            }
        })
        // A tap on a checkbox toggles its task; on a link (shown as one, not being edited), opens it.
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = tapped(e)
        })
        view.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        scope.launch {
            fonts = withContext(Dispatchers.IO) { NoteCore.fonts(context) }
            view.typeface = fonts!!.text
            applyStyle()
            val opened = runCatching { document.open() }.getOrElse { e -> onStatus("error", e.message ?: "Couldn't open it"); return@launch }
            show(opened.text)
            view.isEnabled = true
            if (!document.readOnly) view.requestFocus()
        }
    }

    /** The style's sizes and colours on the view, and a preview drawing in them. */
    private fun applyStyle() {
        val fonts = fonts ?: return
        val density = view.resources.displayMetrics.density
        preview = LivePreview(style, fonts, density) { view.width - view.totalPaddingLeft - view.totalPaddingRight }
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, style.fontSize)
        view.setLineHeight((style.fontSize * style.lineHeight * density).toInt())
        view.setTextColor(style.text.toInt())
        view.setHintTextColor(style.textTertiary.toInt())
        view.highlightColor = style.accentSoft.toInt()
        view.setBackgroundColor(Color.TRANSPARENT)
        val side = (style.paddingHorizontal * density).toInt()
        view.setPadding(side, (style.paddingTop * density).toInt(), side, view.paddingBottom)
        if (document.readOnly) {
            view.showSoftInputOnFocus = false
            view.keyListener = null
        }
        restyle(structure = false)
    }

    /** Shows [text] as the note (opened, or another version), keeping the cursor where it can. */
    private fun show(text: String) {
        loading = true
        val at = view.selectionStart.coerceIn(0, text.length)
        view.setText(text)
        view.setSelection(at.coerceAtMost(view.text.length))
        loading = false
    }

    /** Styles the text again: [structure] when it changed (its spans are worked out again). */
    private fun restyle(structure: Boolean) {
        val preview = preview ?: return
        val text = view.text ?: return
        if (structure) spans = markdownSpans(text.toString())
        val selection = if (view.hasFocus() && !document.readOnly) minOf(view.selectionStart, view.selectionEnd)..maxOf(view.selectionStart, view.selectionEnd) else null
        preview.apply(text, spans, selection, source)
    }

    private fun tapped(e: MotionEvent): Boolean {
        val layout = view.layout ?: return false
        val x = e.x - view.totalPaddingLeft + view.scrollX
        val y = e.y - view.totalPaddingTop + view.scrollY
        val at = layout.getOffsetForHorizontal(layout.getLineForVertical(y.toInt()), x)
        val text = view.text
        text.getSpans(at, at, LivePreview.Checkbox::class.java).firstOrNull()?.let { box ->
            if (document.readOnly) return true
            val start = text.getSpanStart(box)
            val bracket = text.indexOf('[', start).takeIf { it in start until text.getSpanEnd(box) } ?: return true
            text.replace(bracket + 1, bracket + 2, if (box.checked) " " else "x")
            return true
        }
        text.getSpans(at, at, LivePreview.Link::class.java).firstOrNull()?.let { link ->
            // Being edited (its address showing): a tap places the cursor instead.
            if (view.hasFocus() && view.selectionStart in text.getSpanStart(link)..text.getSpanEnd(link)) return false
            onLink(link.url, resolve(link.url))
            return true
        }
        return false
    }

    /** The drive path a link in this note points at (RFC 3986 resolution against its folder); null for web addresses and anchors. */
    private fun resolve(href: String): String? = runCatching {
        val uri = URI(href)
        if (uri.scheme != null || href.startsWith("#") || href.startsWith("//")) return null
        URI(null, null, "/" + parentOf(path), null).resolve(URI(null, null, uri.path, null)).path.removePrefix("/")
    }.getOrNull()

    // ---- what the screen asks ----

    /** Writes what's unsaved now ([then] when done: true if it's all written; if not, it's kept as a draft). */
    fun flush(then: (Boolean) -> Unit = {}) {
        scope.launch { then(document.flush()) }
    }

    /** Coming back: a newer version written elsewhere shows, unless this one's being edited. */
    fun refresh() {
        scope.launch { document.newer()?.let(::show) }
    }

    fun setSource(on: Boolean) {
        source = on
        restyle(structure = false)
    }

    fun setTheme(dark: Boolean) {
        style = noteStyle(dark)
        applyStyle()
    }

    // A conflict: what to keep.
    fun keepMine() { document.keepMine() }
    fun loadTheirs() { scope.launch { runCatching { document.loadTheirs() }.onSuccess(::show) } }
    fun saveCopy(then: (String) -> Unit) { scope.launch { runCatching { document.saveCopy() }.onSuccess { (name, theirs) -> show(theirs); then(name) } } }

    /** Saves, then lets the note go. */
    fun destroy() {
        flush {
            document.close()
            scope.cancel()
        }
    }

    companion object {
        fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun isNote(name: String) = Regex("\\.(md|markdown)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}
