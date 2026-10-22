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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import com.syeyoung.assistant.core.NoteStyle
import com.syeyoung.assistant.core.Span
import com.syeyoung.assistant.core.markdownSpans
import com.syeyoung.assistant.core.renderMath
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
 * live preview (see [LivePreview]), kept in step with every device by [NoteDocument]. [onStatus] hears how that's going;
 * [onLink] opens a followed link (a web address, or with its drive path, a drive file).
 */
class NoteEditor(
    context: Context,
    val path: String,
    private val onStatus: (state: String, message: String?) -> Unit,
    private val onLink: (href: String, resolvedPath: String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val document = NoteDocument(context, path, onStatus, ::remote)
    private var style: NoteStyle = noteStyle(false)
    private var fonts: NoteCore.Fonts? = null
    private var preview: LivePreview? = null
    /** The structure of the text as it is (worked out again when it changes). */
    private var spans: List<Span> = emptyList()
    private var source = false
    /** Setting text that wasn't typed (opening, another version): not an edit. */
    private var loading = true
    /** Being edited (asked for: a note opens as a preview, everything rendered). */
    var editing = false
        private set
    /** How the field takes typing, kept while it's a preview. */
    private var keys: android.text.method.KeyListener? = null
    /** Hears when editing starts or stops (the screen's Edit / Done). */
    var onEditing: (Boolean) -> Unit = {}

    @SuppressLint("ClickableViewAccessibility")
    private val field: EditText = object : EditText(context) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            restyle(structure = false)
        }

        override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect)
            restyle(structure = false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) restyle(structure = false)
        }

        // With a keyboard: Ctrl-B bold, Ctrl-I italic, Ctrl-K a link (as on the web).
        override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
            if (!event.isCtrlPressed || document.readOnly) return super.onKeyShortcut(keyCode, event)
            when (keyCode) {
                KeyEvent.KEYCODE_B -> wrap("**")
                KeyEvent.KEYCODE_I -> wrap("*")
                KeyEvent.KEYCODE_K -> link()
                else -> return super.onKeyShortcut(keyCode, event)
            }
            return true
        }

        // The "/" menu takes the arrow keys and Enter while it's open.
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            (slash.isShowing && slash.onKeyDown(keyCode, event)) || super.onKeyDown(keyCode, event)
    }.apply {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setHorizontallyScrolling(false)
        isEnabled = false
        hint = "Write…"
    }

    /**
     * The note: the text at its full height in a scroll view, which keeps the cursor in sight and,
     * as on the web, has room below the last line (a margin under the text: padding would clip it).
     */
    val view: ScrollView = object : ScrollView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            (field.layoutParams as MarginLayoutParams).bottomMargin = h * 2 / 5
            field.requestLayout()
        }
    }.apply {
        isFillViewport = true
        addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Typed here (not loaded, not another device's): to the others, as it happens.
                if (!loading && !applying) document.edited(start, start + before, s!!.subSequence(start, start + count).toString())
            }
            override fun afterTextChanged(s: Editable) {
                restyle(structure = true)
                if (!loading && !applying) offerBlocks()
            }
        })
        // A tap on a checkbox toggles its task; on a link (shown as one, not being edited), opens it.
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = tapped(e)
        })
        field.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        // Images pasted, dropped or sent by the keyboard: uploaded and linked (the rest is pasted as text).
        ViewCompat.setOnReceiveContentListener(field, arrayOf("image/*")) { _, payload ->
            val split = payload.partition { item -> item.uri?.let { field.context.contentResolver.getType(it)?.startsWith("image/") } == true }
            split.first?.clip?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::insertImage) }
            split.second
        }
        scope.launch {
            fonts = withContext(Dispatchers.IO) { NoteCore.fonts(context) }
            field.typeface = fonts!!.text
            applyStyle()
            val opened = runCatching { document.open() }.getOrElse { e -> onStatus("error", e.message ?: "Couldn't open it"); return@launch }
            show(opened)
            field.isEnabled = true
            // Opened as a preview, at its start; editing is asked for (Edit).
            field.setSelection(0)
            view.scrollTo(0, 0)
            setEditing(false)
        }
    }

    /** The style's sizes and colours on the view, and a preview drawing in them. */
    private fun applyStyle() {
        val fonts = fonts ?: return
        val density = field.resources.displayMetrics.density
        preview = LivePreview(
            style, fonts, density,
            width = { field.width - field.totalPaddingLeft - field.totalPaddingRight },
            viewport = { view.height },
            image = ::image,
        )
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, style.fontSize)
        // (Line height comes from the preview's own span: collapsed lines need none.)
        field.setLineSpacing(0f, 1f)
        field.setTextColor(style.text.toInt())
        field.setHintTextColor(style.textTertiary.toInt())
        field.highlightColor = style.accentSoft.toInt()
        field.setBackgroundColor(Color.TRANSPARENT)
        val side = (style.paddingHorizontal * density).toInt()
        field.setPadding(side, (style.paddingTop * density).toInt(), side, field.paddingBottom)
        restyle(structure = false)
    }

    /**
     * Editing (the cursor and keyboard, Markdown shown where the cursor is) or a preview (all of it
     * rendered, nothing to type into; links open with a tap). Leaving editing saves.
     */
    fun setEditing(on: Boolean) {
        val on = on && !document.readOnly
        if (keys == null) keys = field.keyListener
        editing = on
        field.keyListener = if (on) keys else null
        field.isFocusable = on
        field.isFocusableInTouchMode = on
        field.isCursorVisible = on
        field.showSoftInputOnFocus = on
        val input = field.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (on) {
            field.requestFocus()
            input.showSoftInput(field, 0)
        } else {
            input.hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
            slash.dismiss()
        }
        restyle(structure = false)
        onEditing(on)
    }

    /** Another device's edits being applied (not typed here: not sent back). */
    private var applying = false

    /** Another device's edits, as the document took them: made to the text in order (the cursor and selection move with the text around them). */
    private fun remote(changes: List<com.syeyoung.assistant.core.TextChange>) {
        val text = field.text ?: return
        applying = true
        try {
            for (c in changes) text.replace(c.from.toInt(), c.to.toInt(), c.insert)
        } finally {
            applying = false
        }
    }

    /** Shows [text] as the note (opened, or another version), keeping the cursor where it can. */
    private fun show(text: String) {
        loading = true
        val at = field.selectionStart.coerceIn(0, text.length)
        field.setText(text)
        field.setSelection(at.coerceAtMost(field.text.length))
        loading = false
    }

    /** Styles the text again: [structure] when it changed (its spans are worked out again). */
    private fun restyle(structure: Boolean) {
        val preview = preview ?: return
        val text = field.text ?: return
        if (structure) spans = markdownSpans(text.toString())
        val selection = if (editing && field.hasFocus()) minOf(field.selectionStart, field.selectionEnd)..maxOf(field.selectionStart, field.selectionEnd) else null
        preview.apply(text, spans, selection, source)
        showMathTip(selection)
    }

    // ---- the math being written, typeset just below it (or its TeX error) ----

    private val tipView = MathTipView(context)
    private val tip = android.widget.PopupWindow(tipView, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        // Shown; never takes the keyboard or touches from the text.
        isTouchable = false
        inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED
        elevation = 6 * context.resources.displayMetrics.density
    }

    /** While the cursor is in math (shown as TeX then): it typeset below it, as it's written, or why it doesn't parse. */
    private fun showMathTip(selection: IntRange?) {
        val at = selection?.takeIf { it.first == it.last }?.first
        val span = at?.let { c -> spans.firstOrNull { val e = it.element; e is com.syeyoung.assistant.core.Element.Math && it.range.from.toInt() <= c && c <= it.range.to.toInt() } }
        val math = span?.element as? com.syeyoung.assistant.core.Element.Math
        val layout = field.layout
        if (span == null || math == null || layout == null || !field.isAttachedToWindow) return tip.dismiss()
        if (math.tex.isBlank()) tipView.show("Type TeX…", error = false)
        else try {
            tipView.show(Painted(renderMath(math.tex, math.display, style.fontSize * field.resources.displayMetrics.scaledDensity, style.text)))
        } catch (e: com.syeyoung.assistant.core.RenderException.Invalid) {
            tipView.show(e.v1, error = true)
        } catch (e: com.syeyoung.assistant.core.RenderException) {
            tipView.show(e.message ?: "Can't draw this", error = true)
        }
        // Below the math's last line, at its start.
        val end = span.range.to.toInt().coerceAtMost(field.text.length)
        val line = layout.getLineForOffset(end)
        val x = layout.getPrimaryHorizontal(span.range.from.toInt().coerceAtMost(field.text.length)).toInt() + field.totalPaddingLeft - field.scrollX
        val y = layout.getLineBottom(line) + field.totalPaddingTop - field.scrollY + (4 * field.resources.displayMetrics.density).toInt()
        tipView.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
        val width = tipView.measuredWidth
        val height = tipView.measuredHeight
        val left = x.coerceIn(0, maxOf(0, field.width - width))
        if (tip.isShowing) tip.update(field, left, y - field.height, width, height)
        else tip.showAsDropDown(field, left, y - field.height)
    }

    private fun tapped(e: MotionEvent): Boolean {
        val layout = field.layout ?: return false
        val x = e.x - field.totalPaddingLeft + field.scrollX
        val y = e.y - field.totalPaddingTop + field.scrollY
        val at = layout.getOffsetForHorizontal(layout.getLineForVertical(y.toInt()), x)
        val text = field.text
        text.getSpans(at, at, LivePreview.Checkbox::class.java).firstOrNull()?.let { box ->
            // Ticking a task is an edit: only while editing.
            if (!editing) return true
            val start = text.getSpanStart(box)
            val bracket = text.indexOf('[', start).takeIf { it in start until text.getSpanEnd(box) } ?: return true
            text.replace(bracket + 1, bracket + 2, if (box.checked) " " else "x")
            return true
        }
        text.getSpans(at, at, LivePreview.Link::class.java).firstOrNull()?.let { link ->
            // Being edited (its address showing): a tap places the cursor instead.
            if (editing && field.hasFocus() && field.selectionStart in text.getSpanStart(link)..text.getSpanEnd(link)) return false
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

    // ---- editing ----

    /** Applies a shared editing rule's changes (each against the text before any of them) and its selection. */
    private fun apply(edit: com.syeyoung.assistant.core.Edit) {
        val text = field.text
        for (c in edit.changes.sortedByDescending { it.from }) text.replace(c.from.toInt(), c.to.toInt(), c.insert)
        field.setSelection(edit.anchor.toInt().coerceIn(0, text.length), edit.head.toInt().coerceIn(0, text.length))
    }

    private fun wrap(mark: String) {
        val (a, b) = minOf(field.selectionStart, field.selectionEnd) to maxOf(field.selectionStart, field.selectionEnd)
        apply(com.syeyoung.assistant.core.toggleWrap(field.text.toString(), a.toUInt(), b.toUInt(), mark))
    }

    /** The selection as a link's text, with its address selected to type (Ctrl-K, as on the web). */
    private fun link() {
        val (a, b) = minOf(field.selectionStart, field.selectionEnd) to maxOf(field.selectionStart, field.selectionEnd)
        val label = field.text.substring(a, b).ifEmpty { "link" }
        field.text.replace(a, b, "[$label](https://)")
        val url = a + label.length + 3
        field.setSelection(url, url + 8)
    }

    /** The "/" menu: offered when "/" is typed at a line's start or after a space, narrowed by what's typed after it. */
    private val slash = android.widget.ListPopupWindow(context).apply {
        anchorView = field
        isModal = false
        width = (260 * context.resources.displayMetrics.density).toInt()
        inputMethodMode = android.widget.ListPopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private var slashAt = -1
    private var offered: List<com.syeyoung.assistant.core.SlashItem> = emptyList()

    private fun offerBlocks() {
        val text = field.text
        val cursor = field.selectionStart
        if (cursor < 0 || cursor != field.selectionEnd || document.readOnly) return slash.dismiss()
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val before = text.substring(lineStart, cursor)
        val match = Regex("(?:^|\\s)/([\\w ]*)$").find(before) ?: return slash.dismiss()
        slashAt = lineStart + match.range.last - match.groupValues[1].length
        val query = match.groupValues[1].trim().lowercase()
        offered = com.syeyoung.assistant.core.slashItems().filter { item -> query.isEmpty() || "${item.label} ${item.keywords}".lowercase().split(' ').any { it.startsWith(query) } }
        if (offered.isEmpty()) return slash.dismiss()
        slash.setAdapter(menuAdapter(offered.map { it.label }))
        slash.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            val density = field.resources.displayMetrics.density
            setColor(style.background.toInt())
            setStroke(density.toInt().coerceAtLeast(1), style.rule.toInt())
            cornerRadius = 8 * density
        })
        slash.setOnItemClickListener { _, _, i, _ -> insertBlock(offered[i]) }
        // Below the cursor's line.
        val layout = field.layout ?: return
        val line = layout.getLineForOffset(cursor)
        slash.horizontalOffset = (layout.getPrimaryHorizontal(cursor) + field.totalPaddingLeft).toInt()
        slash.verticalOffset = layout.getLineBottom(line) + field.totalPaddingTop - field.height
        slash.show()
        slash.listView?.setSelection(0)
    }

    /** The "/" menu's rows as the web shows them: the note's font at 14px, padded 5 by 12, in its colours. */
    private fun menuAdapter(labels: List<String>) = object : android.widget.ArrayAdapter<String>(field.context, 0, labels) {
        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val density = context.resources.displayMetrics.density
            val row = (convertView as? android.widget.TextView) ?: android.widget.TextView(context).apply {
                setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            }
            row.text = getItem(position)
            row.typeface = fonts?.text
            row.setTextColor(style.text.toInt())
            return row
        }
    }

    private fun insertBlock(item: com.syeyoung.assistant.core.SlashItem) {
        slash.dismiss()
        val from = slashAt.coerceIn(0, field.text.length)
        field.text.replace(from, field.selectionStart.coerceAtLeast(from), item.text)
        field.setSelection((from + item.cursor.toInt()).coerceAtMost(field.text.length))
    }

    /** An image (pasted, dropped, from the keyboard) uploaded into the note's attachments/ (a free name) and linked at the cursor. */
    private fun insertImage(uri: android.net.Uri) {
        if (document.readOnly) return
        val context = field.context
        val at = field.selectionStart.coerceAtLeast(0)
        scope.launch {
            val link = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri) ?: "image/png"
                    val shown = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
                    val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    val name = com.syeyoung.assistant.core.pastedName(shown, mime, now)
                    val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                    val api = com.syeyoung.assistant.data.Repo.get(context).api()
                    val (made, _) = api.driveCreate(parentOf(path) + "attachments/" + name) { api.driveWrite(it, bytes, mime, absent = true) }
                    com.syeyoung.assistant.core.relativeLink(parentOf(path), made)
                }
            }.getOrElse { e -> android.widget.Toast.makeText(context, "Couldn't add the image: ${e.message}", android.widget.Toast.LENGTH_LONG).show(); return@launch }
            // On a line of its own, as the web does.
            val text = field.text
            val pos = at.coerceAtMost(text.length)
            val lineStart = pos == 0 || text[pos - 1] == '\n'
            val insert = (if (lineStart) "" else "\n") + "![]($link)\n"
            text.insert(pos, insert)
            field.setSelection(pos + insert.length)
        }
    }

    // ---- images in the note ----

    /** Images by their link: loaded, or being loaded (null). */
    private val images = HashMap<String, android.graphics.Bitmap?>()

    /** The image a link in the note shows, if it's loaded; asking starts loading it (the note is styled again when it's in). */
    private fun image(url: String): android.graphics.Bitmap? {
        if (url in images) return images[url]
        images[url] = null
        val context = field.context
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = com.syeyoung.assistant.core.linkPath(parentOf(path), url)?.let { drivePath ->
                        // A drive image: its copy here, downloaded only if it changed.
                        com.syeyoung.assistant.drive.LocalCopies.fetch(context, drivePath).file.readBytes()
                    } ?: URI(url).takeIf { it.scheme == "https" || it.scheme == "http" }?.toURL()?.openStream()?.use { it.readBytes() }
                    bytes?.let(::decode)
                }.getOrNull()
            }
            images[url] = bitmap
            if (bitmap != null) restyle(structure = false)
        }
        return null
    }

    /** A picture's bitmap, decoded smaller when it's big (about 2048px across is plenty here). */
    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- what the screen asks ----


    fun setSource(on: Boolean) {
        source = on
        restyle(structure = false)
    }

    fun setTheme(dark: Boolean) {
        style = noteStyle(dark)
        applyStyle()
    }

    /** Lets the note go (what's typed and not yet sent goes first). */
    fun destroy() {
        tip.dismiss()
        CoroutineScope(Dispatchers.Main).launch {
            document.close()
            scope.cancel()
        }
    }

    companion object {
        fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun isNote(name: String) = Regex("\\.(md|markdown)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}
