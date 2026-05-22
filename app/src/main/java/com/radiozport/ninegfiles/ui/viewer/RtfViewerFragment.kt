package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.databinding.FragmentRtfViewerBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * In-app viewer **and** editor for `.rtf` (Rich Text Format) documents.
 *
 * ## Viewing
 * The fragment parses the RTF byte stream with a single-pass state-machine and
 * converts it to semantic HTML that is rendered in a [WebView] with a
 * theme-adaptive CSS sheet.
 *
 * ## Editing
 * Tapping the pencil icon switches to **edit mode**.  The RTF is parsed a
 * second time into a [SpannableStringBuilder] so that bold, italic, underline,
 * strikethrough, colours, and font-size changes are visible as real text
 * formatting inside the [android.widget.EditText] — no control codes are ever
 * shown to the user.  A compact formatting toolbar (B / I / U / S̶) appears
 * above the editor; tapping a button while text is selected toggles that
 * attribute over the selection.  On save, [serializeSpannableToRtf] walks all
 * span transition points and re-emits a spec-compliant RTF 1.9 document with
 * every character attribute preserved.
 *
 * ## Encrypted files
 * `.rtf.9genc` files encrypted with the device key are decrypted in-memory.
 * Password-encrypted files surface an error asking the user to decrypt via the
 * Secure Vault first.
 */
class RtfViewerFragment : Fragment() {

    private var _binding: FragmentRtfViewerBinding? = null
    private val binding get() = _binding!!

    // ── Document state ────────────────────────────────────────────────────────

    private var currentFile: File? = null
    private var isEditMode = false
    private var isDirty    = false

    // Debounce handler: toolbar state is refreshed at most once per 150 ms so that
    // rapid keystrokes don't queue up expensive getSpans() + setBackgroundColor()
    // calls on the main thread for every character typed.
    private val toolbarHandler  = Handler(Looper.getMainLooper())
    private val toolbarRunnable = Runnable { updateToolbarState() }

    /** Cached HTML body for the WebView (view mode). */
    private var bodyHtml: String = ""

    /**
     * Live rich-text edit buffer.  Populated by [parseRtfToSpannable] at load
     * time and handed to the [android.widget.EditText] when edit mode opens.
     * Formatting spans applied by the toolbar are stored directly in this SSB
     * so that [serializeSpannableToRtf] can read them back on save.
     */
    private var editableContent: SpannableStringBuilder = SpannableStringBuilder()

    /** Text-size steps: S / M / L / XL — starts at Medium (index 1). */
    private var textSizeStep: Int = 1  // 0=S 1=M 2=L 3=XL

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_PATH = "rtfPath"
        fun newInstance(path: String) = RtfViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    // ── Result container ──────────────────────────────────────────────────────

    /** Return type from [loadRtf] so bytes are read only once per load. */
    private data class RtfLoadResult(
        val html:        String,
        val stats:       String,
        val editContent: SpannableStringBuilder
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRtfViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run {
            showError("No document path provided"); return
        }
        val file = File(path)
        if (!file.exists()) { showError("File not found"); return }
        currentFile = file

        binding.tvFileName.text = file.name
        binding.progressBar.isVisible = true

        configureWebView()
        decorateToolbarButtonLabels()
        setupButtonListeners()

        // RTF editor disabled — table editing is not yet supported.
        binding.btnEdit.isVisible = false
        binding.btnEdit.isEnabled = false

        if (file.name.endsWith(".9genc", ignoreCase = true)) {
            // btnEdit is already hidden above; nothing extra needed for encrypted files.
        }

        loadDocument(file)
    }

    override fun onDestroyView() {
        toolbarHandler.removeCallbacks(toolbarRunnable)
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    // ── Toolbar label decoration ──────────────────────────────────────────────

    /**
     * Applies [UnderlineSpan] to the "U" button label and [StrikethroughSpan]
     * to the "S" button label so users can see at a glance what each button does.
     */
    private fun decorateToolbarButtonLabels() {
        binding.btnFmtUnderline.text = SpannableString("U").apply {
            setSpan(UnderlineSpan(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.btnFmtStrike.text = SpannableString("S").apply {
            setSpan(StrikethroughSpan(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ── Button wiring ─────────────────────────────────────────────────────────

    private fun setupButtonListeners() {
        binding.btnEdit.setOnClickListener   { enterEditMode() }
        binding.btnSave.setOnClickListener   { saveDocument() }
        binding.btnCancel.setOnClickListener { maybeExitEditMode() }
        binding.btnShare.setOnClickListener  { currentFile?.let { shareFile(it) } }

        binding.btnTextSize.setOnClickListener {
            if (bodyHtml.isEmpty()) return@setOnClickListener
            textSizeStep = (textSizeStep + 1) % 4
            binding.webView.loadDataWithBaseURL(
                null, wrapHtml(bodyHtml), "text/html", "UTF-8", null)
            val label = listOf("Small", "Medium", "Large", "X-Large")[textSizeStep]
            Snackbar.make(binding.root, "Text size: $label", Snackbar.LENGTH_SHORT).show()
        }

        // Formatting toolbar buttons
        binding.btnFmtBold.setOnClickListener {
            applyToggle { toggleSpan(StyleSpan(Typeface.BOLD), StyleSpan::class.java) {
                it.style == Typeface.BOLD }
            }
        }
        binding.btnFmtItalic.setOnClickListener {
            applyToggle { toggleSpan(StyleSpan(Typeface.ITALIC), StyleSpan::class.java) {
                it.style == Typeface.ITALIC }
            }
        }
        binding.btnFmtUnderline.setOnClickListener {
            applyToggle { toggleSpan(UnderlineSpan(), UnderlineSpan::class.java) { true } }
        }
        binding.btnFmtStrike.setOnClickListener {
            applyToggle { toggleSpan(StrikethroughSpan(), StrikethroughSpan::class.java) { true } }
        }

        // Update toolbar active-state indicators whenever the user taps in the editor
        // (covers cursor repositioning; selection changes are covered by afterTextChanged)
        binding.etEditor.setOnClickListener { updateToolbarState() }

        binding.etEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isEditMode) {
                    isDirty = true
                    // Debounce: cancel any queued toolbar refresh and schedule a
                    // new one 150 ms from now.  This prevents O(spans) work on
                    // every keystroke while still keeping the active-state
                    // indicators accurate after the user pauses.
                    toolbarHandler.removeCallbacks(toolbarRunnable)
                    toolbarHandler.postDelayed(toolbarRunnable, 150L)
                }
            }
        })
    }

    // ── Document loading ──────────────────────────────────────────────────────

    private fun loadDocument(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadRtf(file) }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            when {
                result.isSuccess -> {
                    val (html, stats, content) = result.getOrThrow()
                    bodyHtml        = html
                    editableContent = content
                    binding.tvDocInfo.text = stats
                    binding.webView.loadDataWithBaseURL(
                        null, wrapHtml(html), "text/html", "UTF-8", null)
                    binding.webView.isVisible = true
                }
                else -> showError(
                    "Could not render document: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // ── Edit / view mode switching ────────────────────────────────────────────

    private fun enterEditMode() {
        isEditMode = true
        binding.webView.isVisible          = false
        binding.scrollEditor.isVisible     = true
        binding.formattingToolbar.isVisible = true
        binding.btnEdit.isVisible          = false
        binding.btnSave.isVisible          = true
        binding.btnCancel.isVisible        = true
        binding.btnTextSize.isEnabled      = false
        binding.btnTextSize.alpha          = 0.4f
        binding.tvDocInfo.text             = "Editing"

        // Hand the SpannableStringBuilder directly to the EditText as an editable
        // buffer.  EDITABLE buffer type preserves the spans and keeps them live
        // as the user types, so toolbar toggles take immediate visible effect.
        binding.etEditor.setText(editableContent, TextView.BufferType.EDITABLE)
        // setText triggers afterTextChanged which sets isDirty=true; reset it now
        // so Cancel does not show a spurious "Discard changes?" dialog on entry.
        isDirty = false
        binding.etEditor.requestFocus()
        updateToolbarState()
    }

    private fun exitEditMode() {
        isEditMode = false
        isDirty    = false
        binding.scrollEditor.isVisible     = false
        binding.formattingToolbar.isVisible = false
        binding.webView.isVisible          = true
        binding.btnEdit.isVisible          = true
        binding.btnSave.isVisible          = false
        binding.btnCancel.isVisible        = false
        binding.btnTextSize.isEnabled      = true
        binding.btnTextSize.alpha          = 1f
    }

    private fun maybeExitEditMode() {
        if (isDirty) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Discard changes?")
                .setMessage("Your unsaved edits will be lost.")
                .setPositiveButton("Discard") { _, _ -> exitEditMode() }
                .setNegativeButton("Keep editing", null)
                .show()
        } else {
            exitEditMode()
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveDocument() {
        val file = currentFile ?: return
        if (file.name.endsWith(".9genc", ignoreCase = true)) {
            Snackbar.make(binding.root,
                "Cannot save encrypted RTF files directly.",
                Snackbar.LENGTH_LONG).show()
            return
        }

        // Snapshot the live editable — this SpannableStringBuilder holds every
        // span the user has added or removed via the toolbar.
        val ssb = binding.etEditor.editableText
            ?: SpannableStringBuilder(binding.etEditor.text ?: "")

        // IMPORTANT: serializeSpannableToRtf reads Android Spannable/Editable
        // which is NOT thread-safe.  Perform serialization here on the main
        // thread and pass only the resulting plain String to the IO dispatcher.
        val rtfContent = serializeSpannableToRtf(ssb)

        binding.progressBar.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // Write pre-serialized RTF string; all non-ASCII was already
                    // escaped to \\uN? by the serializer so US-ASCII is safe.
                    file.writeText(rtfContent, Charsets.US_ASCII)
                    true
                } catch (e: Exception) { false }
            }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            if (ok) {
                isDirty = false
                // Re-parse so the WebView reflects the saved state and editableContent
                // is fresh for the next edit session.
                val reloaded = withContext(Dispatchers.IO) {
                    runCatching { loadRtf(file).getOrThrow() }
                }
                if (reloaded.isSuccess) {
                    val (html, stats, content) = reloaded.getOrThrow()
                    bodyHtml        = html
                    editableContent = content
                    binding.tvDocInfo.text = stats
                    binding.webView.loadDataWithBaseURL(
                        null, wrapHtml(html), "text/html", "UTF-8", null)
                }
                exitEditMode()
                Snackbar.make(binding.root, "Saved successfully",
                    Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root,
                    "Save failed — check storage permissions",
                    Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ── Formatting toolbar logic ──────────────────────────────────────────────

    /**
     * Runs [block] on the editor's current selection, then marks the document
     * dirty and refreshes toolbar active states.
     */
    private fun applyToggle(block: SpannableStringBuilder.() -> Unit) {
        val ssb = binding.etEditor.editableText ?: return
        (ssb as SpannableStringBuilder).block()
        isDirty = true
        updateToolbarState()
    }

    /**
     * Toggles a span of type [T] over the current selection in this
     * [SpannableStringBuilder].
     *
     * Behaviour matches Word / Google Docs:
     * - If every character in the selection is already covered by a matching
     *   span → remove those spans (toggle off).
     * - Otherwise → remove any partial spans and apply one new span over the
     *   whole selection (toggle on, promoting partial formatting to full).
     *
     * @param spanClass  The span class to inspect (e.g. [UnderlineSpan]).
     * @param predicate  Extra filter for distinguishing sub-types, e.g. bold
     *                   vs italic [StyleSpan].
     */
    private fun <T : Any> SpannableStringBuilder.toggleSpan(
        newSpan: T,
        spanClass: Class<T>,
        predicate: (T) -> Boolean
    ) {
        val selStart = binding.etEditor.selectionStart
        val selEnd   = binding.etEditor.selectionEnd
        if (selStart < 0 || selEnd < 0 || selStart >= selEnd) return

        val existing = getSpans(selStart, selEnd, spanClass).filter(predicate)

        // "Fully covered" = at least one span starts at-or-before selStart
        // AND ends at-or-after selEnd, with no gaps (simplified: check coverage
        // at first and last character).
        val fullyOn = existing.any { s ->
            getSpanStart(s) <= selStart && getSpanEnd(s) >= selEnd
        }

        // Remove all overlapping spans of this type regardless, so we get a
        // clean state before (conditionally) re-applying.
        existing.forEach { removeSpan(it) }

        if (!fullyOn) {
            // Toggle on — one unbroken span over the whole selection.
            setSpan(newSpan, selStart, selEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Toggle off — spans were just removed above, nothing more to do.
    }

    /**
     * Reflects the formatting active at the current cursor position (or
     * selection start) in the toolbar button appearance.
     */
    private fun updateToolbarState() {
        if (!isEditMode) return
        val ssb = binding.etEditor.editableText ?: return
        val pos = (binding.etEditor.selectionStart - 1).coerceAtLeast(0)
            .coerceAtMost((ssb.length - 1).coerceAtLeast(0))

        val styleSpans = ssb.getSpans(pos, pos + 1, StyleSpan::class.java)
        val isBold      = styleSpans.any { it.style == Typeface.BOLD }
        val isItalic    = styleSpans.any { it.style == Typeface.ITALIC }
        val isUnderline = ssb.getSpans(pos, pos + 1, UnderlineSpan::class.java).isNotEmpty()
        val isStrike    = ssb.getSpans(pos, pos + 1, StrikethroughSpan::class.java).isNotEmpty()

        setToolbarButtonActive(binding.btnFmtBold,      isBold)
        setToolbarButtonActive(binding.btnFmtItalic,    isItalic)
        setToolbarButtonActive(binding.btnFmtUnderline, isUnderline)
        setToolbarButtonActive(binding.btnFmtStrike,    isStrike)
    }

    private fun setToolbarButtonActive(btn: MaterialButton, active: Boolean) {
        val ctx = btn.context
        if (active) {
            btn.setTextColor(MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorPrimary, Color.BLUE))
            btn.setBackgroundColor(MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY))
        } else {
            btn.setTextColor(MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            btn.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    // ── WebView configuration ─────────────────────────────────────────────────

    private fun configureWebView() {
        binding.webView.apply {
            webViewClient = WebViewClient()
            settings.apply {
                javaScriptEnabled    = false
                loadWithOverviewMode = false
                useWideViewPort      = false
                textZoom             = 100
                cacheMode            = WebSettings.LOAD_NO_CACHE
                setSupportZoom(true)
                builtInZoomControls    = true
                displayZoomControls    = false
            }
            isVisible = false
        }
    }

    // ── RTF loading ───────────────────────────────────────────────────────────

    private suspend fun loadRtf(file: File): Result<RtfLoadResult> = runCatching {
        val bytes: ByteArray = if (EncryptionUtils.isEncrypted(file)) {
            when (EncryptionUtils.detectFormat(file)) {
                EncryptionUtils.EncryptionFormat.DEVICE_KEY ->
                    EncryptionUtils.decryptDeviceToBytes(file) {
                        DeviceKeyManager.decryptSessionKey(it)
                    } ?: error("Decryption failed — file may be encrypted for a different device")
                EncryptionUtils.EncryptionFormat.PASSWORD_BASED ->
                    error("Password-encrypted file. Decrypt it via the Secure Vault first.")
                null -> error("Unknown encryption format")
            }
        } else {
            file.readBytes()
        }

        val (html, plainText) = parseRtf(bytes)
        val editContent       = parseRtfToSpannable(bytes)

        val wordCount = plainText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val stats     = "$wordCount words · ${plainText.length} characters"

        RtfLoadResult(html, stats, editContent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED FORMATTING STATE
    // FmtState is used by both parse paths (HTML and Spannable).
    // applyFmtState updates it and returns any literal text to emit.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimal mutable formatting state; one instance is pushed onto the stack
     * at each `{` and popped at `}`.
     */
    private data class FmtState(
        var bold:         Boolean = false,
        var italic:       Boolean = false,
        var underline:    Boolean = false,
        var strike:       Boolean = false,
        var superscript:  Boolean = false,
        var subscript:    Boolean = false,
        var fontSize:     Int     = 24,   // half-points; 24 = 12 pt
        var colorIndex:   Int     = 0,
        var highlightIndex: Int   = 0,
        var align:        String  = "left",
        var leftIndent:   Int     = 0,
        var isSkip:       Boolean = false
    ) {
        fun copy() = FmtState(bold, italic, underline, strike, superscript, subscript,
            fontSize, colorIndex, highlightIndex, align, leftIndent, isSkip)
    }

    /**
     * Updates [cur]'s [FmtState] for the given RTF control word and optional
     * numeric parameter.
     *
     * @return Literal text that the caller should emit at the current position
     *   (e.g. `"\n"` for `\par`, `"\u2014"` for `\emdash`), or **`null`** if
     *   the word is a pure state change or is silently ignored.
     */
    private fun applyFmtState(word: String, num: Int, cur: () -> FmtState): String? {
        val hasNum = num != Int.MIN_VALUE
        when (word) {

            // ── Paragraph / structural breaks ────────────────────────────
            "par", "page", "sect", "column" -> return "\n"
            "line" -> return "\n"
            "tab"  -> return "\t"

            // ── Character formatting ──────────────────────────────────────
            "b"              -> cur().bold       = !(hasNum && num == 0)
            "i"              -> cur().italic     = !(hasNum && num == 0)
            "ul","uld","uldb","ulwave"
                             -> cur().underline  = !(hasNum && num == 0)
            "ulnone"         -> cur().underline  = false
            "strike","striked"
                             -> cur().strike     = !(hasNum && num == 0)
            "super"          -> { cur().superscript = true;  cur().subscript   = false }
            "sub"            -> { cur().subscript   = true;  cur().superscript = false }
            "nosupersub"     -> { cur().superscript = false; cur().subscript   = false }
            "fs"             -> if (hasNum && num > 0) cur().fontSize     = num
            "cf"             -> cur().colorIndex    = if (hasNum) num else 0
            "cb","highlight" -> cur().highlightIndex = if (hasNum) num else 0

            "plain" -> cur().also {
                it.bold = false; it.italic = false; it.underline = false
                it.strike = false; it.superscript = false; it.subscript = false
                it.fontSize = 24; it.colorIndex = 0; it.highlightIndex = 0
            }
            "pard"  -> cur().also {
                it.align = "left"; it.leftIndent = 0
                it.bold = false; it.italic = false; it.underline = false
                it.strike = false; it.superscript = false; it.subscript = false
                it.fontSize = 24; it.colorIndex = 0; it.highlightIndex = 0
            }

            // ── Paragraph alignment / indent ──────────────────────────────
            "ql" -> cur().align = "left"
            "qc" -> cur().align = "center"
            "qr" -> cur().align = "right"
            "qj" -> cur().align = "justify"
            "li" -> if (hasNum) cur().leftIndent = num

            // ── Special characters ────────────────────────────────────────
            "emdash"    -> return "\u2014"
            "endash"    -> return "\u2013"
            "bullet"    -> return "\u2022"
            "lquote"    -> return "\u2018"
            "rquote"    -> return "\u2019"
            "ldblquote" -> return "\u201C"
            "rdblquote" -> return "\u201D"
            "enspace"   -> return "\u2002"
            "emspace"   -> return "\u2003"
            "qmspace"   -> return "\u2004"
        }
        return null  // state-only update, or silently ignored control word
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF → HTML PARSER (view mode)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseRtf(bytes: ByteArray): Pair<String, String> {
        val src = String(bytes, Charsets.ISO_8859_1)
        if (src.length < 5 || !src.startsWith("{\\rtf")) {
            val plain = String(bytes, Charsets.UTF_8)
            return Pair(
                "<p>${plain.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}</p>",
                plain)
        }

        val colorTable = extractColorTable(src)

        val html      = StringBuilder()
        val plainText = StringBuilder()
        val stack     = ArrayDeque<FmtState>()
        stack.addLast(FmtState())

        val paraHtml  = StringBuilder()
        val paraPlain = StringBuilder()

        var inTable           = false
        // \pard resets RTF's "in-table" state; \intbl (always present in cell
        // paragraphs as \pard\intbl) re-asserts it.  We use a deferred flag rather
        // than closing the table immediately on \pard, because every cell starts
        // with \pard\intbl — we must wait to see whether \intbl follows.
        var pendingTableClose = false
        var tableHtml         = StringBuilder()

        var i = 0
        val len = src.length

        fun cur(): FmtState = stack.last()

        // Flush a completed table to the main html stream at its correct position.
        // Called whenever we know the current paragraph is outside any table
        // (i.e. \pard was seen and \intbl did NOT follow before content/\par).
        fun closeTableIfPending() {
            if (!pendingTableClose) return
            tableHtml.append("</table>\n")
            html.append(tableHtml)
            tableHtml         = StringBuilder()
            inTable           = false
            pendingTableClose = false
        }

        fun flushParagraph(forceBlank: Boolean = false) {
            closeTableIfPending()
            if (inTable) { tableHtml.append(paraHtml); paraHtml.clear(); paraPlain.append('\n'); return }
            val content = paraHtml.toString()
            if (content.isNotEmpty()) {
                val alignStyle  = when (cur().align) {
                    "center"  -> " style='text-align:center'"
                    "right"   -> " style='text-align:right'"
                    "justify" -> " style='text-align:justify'"
                    else      -> ""
                }
                val indentStyle = if (cur().leftIndent > 0)
                    " style='margin-left:${cur().leftIndent / 20}px'" else ""
                val style = if (alignStyle.isNotEmpty() && indentStyle.isNotEmpty())
                    " style='text-align:${cur().align};margin-left:${cur().leftIndent / 20}px'"
                else alignStyle.ifEmpty { indentStyle }
                html.append("<p$style>$content</p>\n")
                plainText.append(paraPlain).append('\n')
            } else if (forceBlank) {
                html.append("<p class='blank'></p>\n")
                plainText.append('\n')
            }
            paraHtml.clear(); paraPlain.clear()
        }

        fun applyFormatting(text: String): String {
            if (text.isEmpty()) return ""
            var s = text
            val f = cur()
            if (f.fontSize != 24) {
                val em = "%.2f".format(f.fontSize / 2.0 / 12.0)
                s = "<span style='font-size:${em}em'>$s</span>"
            }
            if (f.highlightIndex > 0) {
                val bg = colorTable.getOrNull(f.highlightIndex - 1) ?: ""
                if (bg.isNotEmpty()) s = "<mark style='background:$bg'>$s</mark>"
            }
            if (f.colorIndex > 0) {
                val fg = colorTable.getOrNull(f.colorIndex - 1) ?: ""
                if (fg.isNotEmpty()) s = "<span style='color:$fg'>$s</span>"
            }
            if (f.superscript) s = "<sup>$s</sup>"
            if (f.subscript)   s = "<sub>$s</sub>"
            if (f.strike)      s = "<s>$s</s>"
            if (f.underline)   s = "<u>$s</u>"
            if (f.italic)      s = "<em>$s</em>"
            if (f.bold)        s = "<strong>$s</strong>"
            return s
        }

        fun appendText(raw: String) {
            if (cur().isSkip) return
            closeTableIfPending()
            val escaped = raw.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            paraHtml.append(applyFormatting(escaped))
            paraPlain.append(raw)
        }

        while (i < len) {
            when (src[i]) {
                '{' -> { stack.addLast(cur().copy()); i++ }
                '}' -> { if (stack.size > 1) stack.removeLast(); i++ }
                '\\' -> {
                    i++; if (i >= len) break
                    when (src[i]) {
                        '\\' -> { appendText("\\"); i++ }
                        '{'  -> { appendText("{");  i++ }
                        '}'  -> { appendText("}");  i++ }
                        '\r', '\n' -> { flushParagraph(false); i++ }
                        '\'' -> {
                            i++
                            if (i + 1 < len) {
                                val code = src.substring(i, i + 2).toIntOrNull(16) ?: 0
                                appendText(code.toChar().toString()); i += 2
                            }
                        }
                        '-' -> { appendText("\u00AD"); i++ }
                        '_' -> { appendText("\u2011"); i++ }
                        '~' -> { appendText("\u00A0"); i++ }
                        '*' -> { cur().isSkip = true; i++ }
                        else -> {
                            val wordStart = i
                            while (i < len && src[i].isLetter()) i++
                            val word = src.substring(wordStart, i)

                            var negative = false
                            if (i < len && src[i] == '-') { negative = true; i++ }
                            val numStart = i
                            while (i < len && src[i].isDigit()) i++
                            val numStr = src.substring(numStart, i)
                            val num = if (numStr.isNotEmpty())
                                (if (negative) -1 else 1) * numStr.toInt()
                            else Int.MIN_VALUE
                            if (i < len && src[i] == ' ') i++

                            if (cur().isSkip && word != "*") {
                                // discard — inside \* ignorable destination
                            } else if (word == "u" && num != Int.MIN_VALUE) {
                                val cp = if (num < 0) num + 65536 else num
                                try { appendText(String(Character.toChars(cp))) }
                                catch (_: Exception) { appendText("?") }
                                if (i < len && src[i] != '{' && src[i] != '}' && src[i] != '\\') i++
                            } else {
                                applyControlWord(word, num, ::cur, ::appendText,
                                    ::flushParagraph, paraHtml, html, plainText,
                                    inTable, tableHtml, colorTable)

                                when (word) {
                                    "pard"  -> {
                                        // \pard tentatively exits table mode; \intbl will
                                        // cancel this if the paragraph is still a table cell.
                                        if (inTable) pendingTableClose = true
                                    }
                                    "trowd" -> {
                                        // A new row definition may start a brand-new table
                                        // immediately after a previous one ended (bug: two
                                        // tables would merge without this flush).
                                        closeTableIfPending()
                                        if (!inTable) { inTable = true; tableHtml = StringBuilder("<table>\n") }
                                        tableHtml.append("<tr>")
                                    }
                                    "row"   -> { flushParagraph(false); tableHtml.append("</tr>\n") }
                                    "cell"  -> {
                                        val ct = paraHtml.toString(); paraHtml.clear(); paraPlain.clear()
                                        tableHtml.append("<td>$ct</td>")
                                    }
                                    "intbl" -> {
                                        // Cancels a pending table-close: this paragraph IS
                                        // still a table cell (\pard\intbl pattern).
                                        pendingTableClose = false
                                        inTable = true
                                    }
                                }
                            }
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> { if (!cur().isSkip) appendText(src[i].toString()); i++ }
            }
        }

        flushParagraph(false)
        if (inTable) { tableHtml.append("</table>\n"); html.append(tableHtml) }

        return Pair(html.toString(), plainText.toString().trim())
    }

    private fun applyControlWord(
        word: String, num: Int,
        cur: () -> FmtState,
        appendText: (String) -> Unit,
        flushParagraph: (Boolean) -> Unit,
        paraHtml: StringBuilder,
        html: StringBuilder,
        plainText: StringBuilder,
        inTable: Boolean,
        tableHtml: StringBuilder,
        colorTable: List<String>
    ) {
        val hasNum = num != Int.MIN_VALUE
        when (word) {
            "par"    -> flushParagraph(true)
            "line"   -> { paraHtml.append("<br>"); plainText.append('\n') }
            "page"   -> { flushParagraph(true); html.append("<hr class='page-break'>\n") }
            "sect"   -> { flushParagraph(true); html.append("<hr>\n") }
            "column" -> flushParagraph(true)
            "tab"    -> { paraHtml.append("&nbsp;&nbsp;&nbsp;&nbsp;"); plainText.append('\t') }

            "b"              -> cur().bold        = !(hasNum && num == 0)
            "i"              -> cur().italic      = !(hasNum && num == 0)
            "ul","uld","uldb","ulwave"
                             -> cur().underline   = !(hasNum && num == 0)
            "ulnone"         -> cur().underline   = false
            "strike","striked"
                             -> cur().strike      = !(hasNum && num == 0)
            "super"          -> { cur().superscript = true;  cur().subscript   = false }
            "sub"            -> { cur().subscript   = true;  cur().superscript = false }
            "nosupersub"     -> { cur().superscript = false; cur().subscript   = false }
            "fs"             -> if (hasNum && num > 0) cur().fontSize     = num
            "cf"             -> cur().colorIndex    = if (hasNum) num else 0
            "cb","highlight" -> cur().highlightIndex = if (hasNum) num else 0

            "plain" -> cur().also {
                it.bold = false; it.italic = false; it.underline = false
                it.strike = false; it.superscript = false; it.subscript = false
                it.fontSize = 24; it.colorIndex = 0; it.highlightIndex = 0
            }
            "pard"  -> cur().also {
                it.align = "left"; it.leftIndent = 0
                it.bold = false; it.italic = false; it.underline = false
                it.strike = false; it.superscript = false; it.subscript = false
                it.fontSize = 24; it.colorIndex = 0; it.highlightIndex = 0
            }

            "ql" -> cur().align = "left"
            "qc" -> cur().align = "center"
            "qr" -> cur().align = "right"
            "qj" -> cur().align = "justify"
            "li" -> if (hasNum) cur().leftIndent = num

            // NOTE: \uN? Unicode escapes are handled in the main loop where `i` is
            // accessible, so the mandatory fallback character can be consumed.

            "emdash"    -> appendText("\u2014")
            "endash"    -> appendText("\u2013")
            "bullet"    -> appendText("\u2022")
            "lquote"    -> appendText("\u2018")
            "rquote"    -> appendText("\u2019")
            "ldblquote" -> appendText("\u201C")
            "rdblquote" -> appendText("\u201D")
            "enspace"   -> appendText("\u2002")
            "emspace"   -> appendText("\u2003")
            "qmspace"   -> appendText("\u2004")
            "pntext"    -> { /* list item bullet — handled via \bullet / literal */ }

            "rtf1","ansi","ansicpg","mac","pc","pca",
            "deff","deflang","deflangfe",
            "fonttbl","colortbl","stylesheet","info",
            "listtable","listoverridetable",
            "revtbl","rsidtbl",
            "header","footer","headerl","headerr","headerf",
            "footerl","footerr","footerf",
            "fldinst","fldrslt",
            "pict","object","datafield",
            "shp","shpinst","sp",
            "wgrffmtfilter",
            "themedata","colorschememapping",
            "xmlns","xmlopen","xmlclose",
            "ts","tscellpaddfl","tscellpaddfr","tscellpaddft","tscellpaddfb",
            "cellx" -> { /* silently skip */ }

            else -> { /* unknown control word — ignore per RTF spec */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF → SPANNABLE PARSER (edit mode)
    // Reuses the same state machine and FmtState as the HTML parser, but emits
    // a SpannableStringBuilder instead of HTML strings.  applyFmtState handles
    // all FmtState transitions; content output is managed inline below.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses [bytes] as RTF and returns a [SpannableStringBuilder] where all
     * character formatting (bold, italic, underline, strikethrough, colour,
     * relative font size, superscript, subscript) is represented as Android
     * [android.text.style] spans.
     *
     * The returned SSB is used directly as the EditText's editable buffer, so
     * the user sees formatted text — never RTF control codes.
     */
    private fun parseRtfToSpannable(bytes: ByteArray): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val src = String(bytes, Charsets.ISO_8859_1)

        if (src.length < 5 || !src.startsWith("{\\rtf")) {
            ssb.append(String(bytes, Charsets.UTF_8))
            return ssb
        }

        val colorTable = extractColorTable(src)

        // ── Table-preservation tracking ───────────────────────────────────────
        // Tables cannot be represented in a plain EditText, so instead of
        // bleeding cell text into the editable SSB we:
        //   1. Suppress all content that belongs to a table from emit().
        //   2. Insert a single '\uE000' placeholder character at each table site.
        //   3. Attach a TablePlaceholderSpan to that char carrying the verbatim
        //      RTF source of the table (from \trowd to the end of the last \row).
        // serializeSpannableToRtf recognises these spans and re-emits the raw
        // RTF on save, so tables survive a round-trip through the editor.
        var tableInParse      = false          // currently inside a table block
        var tableStartSrcIdx  = -1             // src index of '\'  before \trowd
        var lastRowEndSrcIdx  = -1             // src index after last \row (incl. trailing space)
        var pendingTableClose = false          // \pard seen in table; waiting to see \intbl

        fun flushTablePlaceholder() {
            if ((!tableInParse && !pendingTableClose) || lastRowEndSrcIdx < 0) return
            val rawRtf = src.substring(tableStartSrcIdx, lastRowEndSrcIdx)
            val pos = ssb.length
            ssb.append('\uE000')
            ssb.setSpan(TablePlaceholderSpan(rawRtf),
                pos, pos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tableInParse      = false
            pendingTableClose = false
            tableStartSrcIdx  = -1
            lastRowEndSrcIdx  = -1
        }

        // SpanRun: a character range + the FmtState snapshot active during it.
        // All spans are applied in one pass at the end so we avoid O(n²) setSpan
        // calls interleaved with text appends.
        //
        // Runs are COALESCED: if the incoming FmtState is identical to the last
        // run and the text is contiguous, we extend the existing run instead of
        // opening a new one.  Without coalescing, each literal character becomes
        // its own SpanRun (the main loop emits one char at a time), ballooning a
        // 5 KB document to ~5 000 runs → ~50 000 setSpan() calls → the EditText
        // span array becomes enormous, making every keystroke O(N-spans) in the
        // layout engine and causing the visible freeze.  With coalescing, a whole
        // paragraph with uniform formatting collapses to a single run regardless
        // of character count.
        data class SpanRun(val s: Int, val e: Int, val f: FmtState)
        val runs = mutableListOf<SpanRun>()

        val stack = ArrayDeque<FmtState>()
        stack.addLast(FmtState())
        fun cur(): FmtState = stack.last()

        fun emit(raw: String) {
            if (cur().isSkip || raw.isEmpty()) return
            // If a \pard was seen inside a table but \intbl hasn't confirmed
            // it's still a table cell, any non-suppressed emit means we've left
            // the table → flush the placeholder first.
            if (pendingTableClose) flushTablePlaceholder()
            // While we are confirmed to be inside a table, suppress all content
            // from the editable SSB; it will be preserved verbatim via rawRtf.
            if (tableInParse) return
            val start = ssb.length
            ssb.append(raw)
            val curFmt = cur()
            val last = runs.lastOrNull()
            if (last != null && last.e == start && last.f == curFmt) {
                // Same format as the immediately preceding run and contiguous →
                // extend it rather than opening a new SpanRun.  This is the key
                // coalescing step: the main loop emits one character at a time, so
                // without this check every character becomes its own SpanRun,
                // inflating a 5 KB document to ~5 000 runs and ~50 000 setSpan()
                // calls.  The EditText span array then becomes so large that every
                // keystroke triggers an O(N-spans) layout pass, causing the freeze.
                runs[runs.size - 1] = last.copy(e = ssb.length)
            } else {
                runs.add(SpanRun(start, ssb.length, curFmt.copy()))
            }
        }

        // Paragraph break in the editable → single newline character.
        fun parBreak() { emit("\n") }

        var i = 0
        val len = src.length

        while (i < len) {
            when (src[i]) {
                '{' -> { stack.addLast(cur().copy()); i++ }
                '}' -> { if (stack.size > 1) stack.removeLast(); i++ }
                '\\' -> {
                    i++; if (i >= len) break
                    when (src[i]) {
                        '\\' -> { emit("\\"); i++ }
                        '{'  -> { emit("{");  i++ }
                        '}'  -> { emit("}");  i++ }
                        '\r', '\n' -> { parBreak(); i++ }
                        '\'' -> {
                            i++
                            if (i + 1 < len) {
                                val code = src.substring(i, i + 2).toIntOrNull(16) ?: 0
                                emit(code.toChar().toString()); i += 2
                            }
                        }
                        '-' -> { emit("\u00AD"); i++ }   // optional hyphen
                        '_' -> { emit("\u2011"); i++ }   // non-breaking hyphen
                        '~' -> { emit("\u00A0"); i++ }   // non-breaking space
                        '*' -> { cur().isSkip = true; i++ }
                        else -> {
                            // Read control word
                            val wordStart = i
                            while (i < len && src[i].isLetter()) i++
                            val word = src.substring(wordStart, i)

                            // Read optional numeric parameter
                            var negative = false
                            if (i < len && src[i] == '-') { negative = true; i++ }
                            val numStart = i
                            while (i < len && src[i].isDigit()) i++
                            val numStr = src.substring(numStart, i)
                            val num = if (numStr.isNotEmpty())
                                (if (negative) -1 else 1) * numStr.toInt()
                            else Int.MIN_VALUE
                            if (i < len && src[i] == ' ') i++  // consume trailing space

                            if (cur().isSkip && word != "*") {
                                // inside \* ignorable destination — discard
                            } else if (word == "u" && num != Int.MIN_VALUE) {
                                // Unicode escape \uN?: emit codepoint, skip fallback char
                                val cp = if (num < 0) num + 65536 else num
                                try { emit(String(Character.toChars(cp))) }
                                catch (_: Exception) { emit("?") }
                                if (i < len && src[i] != '{' && src[i] != '}' && src[i] != '\\') i++
                            } else {
                                // Delegate to shared state updater; emit any returned text
                                val content = applyFmtState(word, num, ::cur)
                                if (content != null) emit(content)

                                // ── Table-structure control words ─────────────────────────────
                                // applyFmtState ignores these; handle them here to track table
                                // boundaries and build the TablePlaceholderSpan payload.
                                when (word) {
                                    "trowd" -> {
                                        // A \trowd may follow immediately after a \row from the
                                        // *same* table (next row) or start a brand-new table.
                                        // flushTablePlaceholder() is a no-op when pendingTableClose
                                        // is false, so adjacent tables are handled correctly.
                                        flushTablePlaceholder()
                                        if (!tableInParse) {
                                            // backslashPos = wordStart - 1 (the '\' character)
                                            tableStartSrcIdx = wordStart - 1
                                            tableInParse     = true
                                        }
                                    }
                                    "row" -> {
                                        // End of one row; record end position so the raw RTF
                                        // captured up to here (inclusive) is complete when the
                                        // table is later flushed.  Do NOT set pendingTableClose
                                        // here — the table may continue with more rows.  It is
                                        // \pard (without a following \intbl) that signals the
                                        // table has truly ended.
                                        lastRowEndSrcIdx = i
                                    }
                                    "intbl" -> {
                                        // This paragraph IS a table cell → cancel any tentative
                                        // table-close that was set by a preceding \pard.
                                        pendingTableClose = false
                                        tableInParse      = true
                                    }
                                    "pard" -> {
                                        // \pard resets the "in-table" hint.  If we were in a
                                        // table the close is now pending until we see \intbl or
                                        // non-table content.
                                        if (tableInParse) pendingTableClose = true
                                    }
                                }
                            }
                        }
                    }
                }
                '\r', '\n' -> i++   // bare newlines are ignored in RTF
                else -> { if (!cur().isSkip) emit(src[i].toString()); i++ }
            }
        }

        // Flush any table that was open at end-of-document (no trailing \pard).
        if (tableInParse || pendingTableClose) flushTablePlaceholder()

        // Strip trailing newlines that were added by the final \par
        while (ssb.isNotEmpty() && ssb.last() == '\n')
            ssb.delete(ssb.length - 1, ssb.length)

        // ── Apply collected spans ─────────────────────────────────────────────
        // Clamp end positions: the trailing-newline trim above may have shortened
        // the SSB after SpanRun indices were recorded, making some end values
        // point beyond the new length and causing setSpan to throw.
        val X = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        for ((s, eRaw, f) in runs) {
            val e = eRaw.coerceAtMost(ssb.length)
            if (s >= e) continue
            // Bold and italic are separate StyleSpans so the toolbar can toggle
            // each independently without needing to split BOLD_ITALIC spans.
            if (f.bold)        ssb.setSpan(StyleSpan(Typeface.BOLD),   s, e, X)
            if (f.italic)      ssb.setSpan(StyleSpan(Typeface.ITALIC), s, e, X)
            if (f.underline)   ssb.setSpan(UnderlineSpan(),            s, e, X)
            if (f.strike)      ssb.setSpan(StrikethroughSpan(),        s, e, X)
            if (f.superscript) ssb.setSpan(SuperscriptSpan(),          s, e, X)
            if (f.subscript)   ssb.setSpan(SubscriptSpan(),            s, e, X)
            if (f.fontSize != 24)
                ssb.setSpan(RelativeSizeSpan(f.fontSize / 24f),        s, e, X)
            if (f.colorIndex > 0) {
                val hex = colorTable.getOrElse(f.colorIndex - 1) { "" }
                if (hex.isNotEmpty()) try {
                    ssb.setSpan(ForegroundColorSpan(Color.parseColor(hex)), s, e, X)
                } catch (_: Exception) {}
            }
            if (f.highlightIndex > 0) {
                val hex = colorTable.getOrElse(f.highlightIndex - 1) { "" }
                if (hex.isNotEmpty()) try {
                    ssb.setSpan(BackgroundColorSpan(Color.parseColor(hex)), s, e, X)
                } catch (_: Exception) {}
            }
        }

        return ssb
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF COLOUR TABLE
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractColorTable(src: String): List<String> {
        val colors = mutableListOf<String>()
        var start = src.indexOf("{\\*\\colortbl")
        if (start < 0) start = src.indexOf("{\\colortbl")
        if (start < 0) return colors
        var depth = 0
        val buf = StringBuilder()
        var i = start
        while (i < src.length) {
            when (src[i]) {
                '{' -> { depth++; i++ }
                '}' -> {
                    depth--; i++
                    if (depth == 0) { parseColorEntries(buf.toString(), colors); break }
                }
                else -> { if (depth > 0) buf.append(src[i]); i++ }
            }
        }
        return colors
    }

    private fun parseColorEntries(colortblContent: String, out: MutableList<String>) {
        for (entry in colortblContent.split(';')) {
            if (entry.isBlank()) { out.add(""); continue }
            val r = Regex("\\\\red(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val g = Regex("\\\\green(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val b = Regex("\\\\blue(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add("#%02x%02x%02x".format(r, g, b))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TABLE PLACEHOLDER SPAN
    //
    // Inserted into the SpannableStringBuilder (as a single \uE000 character)
    // by parseRtfToSpannable to represent an RTF table block whose structure
    // cannot be expressed in a plain EditText.  The span carries the verbatim
    // RTF source of the table so that serializeSpannableToRtf can re-emit it
    // unchanged on save, preserving every \trowd / \cell / \row / \cellx
    // control word that the edit-mode EditText would otherwise lose.
    // ─────────────────────────────────────────────────────────────────────────

    /** Opaque marker attached to the single '\uE000' placeholder character that
     *  represents a complete RTF table block inside the editable SSB. */
    private class TablePlaceholderSpan(val rawRtf: String)

    // ─────────────────────────────────────────────────────────────────────────
    // SPANNABLE → RTF SERIALISER (save path)
    //
    // Walks all span transition points in the SpannableStringBuilder.  Between
    // any two consecutive transition points, the set of active spans is constant
    // by construction, so the formatting state changes only at boundaries.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serialises a [SpannableStringBuilder] into a spec-compliant RTF 1.9
     * document string.
     *
     * Supported spans → RTF control words:
     * - [StyleSpan] (BOLD) → `\b … \b0`
     * - [StyleSpan] (ITALIC) → `\i … \i0`
     * - [UnderlineSpan] → `\ul … \ulnone`
     * - [StrikethroughSpan] → `\strike … \strike0`
     * - [SuperscriptSpan] → `\super … \nosupersub`
     * - [SubscriptSpan] → `\sub … \nosupersub`
     * - [RelativeSizeSpan] → `\fsN` (relative to 12 pt base = 24 half-points)
     * - [ForegroundColorSpan] → `\cfN` with auto-built `\colortbl`
     * - [BackgroundColorSpan] → `\highlightN` with auto-built `\colortbl`
     *
     * Newline characters in the SSB become `\par` paragraph breaks.
     * Non-ASCII characters are emitted as `\uN?` Unicode escapes.
     * The output is 7-bit-clean ASCII.
     */
    private fun serializeSpannableToRtf(ssb: Editable): String {
        val sb  = StringBuilder()
        val len = ssb.length

        // ── 1. Collect all unique colours from FG / BG spans ─────────────────
        val allColors = mutableListOf<Int>()
        for (sp in ssb.getSpans(0, len, ForegroundColorSpan::class.java)) {
            val c = sp.foregroundColor
            if (c != 0 && c !in allColors) allColors.add(c)
        }
        for (sp in ssb.getSpans(0, len, BackgroundColorSpan::class.java)) {
            val c = sp.backgroundColor
            if (c != 0 && c !in allColors) allColors.add(c)
        }
        // Map Android Color int → 1-based RTF \colortbl index
        val colorIdx: Map<Int, Int> = allColors.withIndex().associate { (i, c) -> c to (i + 1) }

        // ── 2. RTF header ─────────────────────────────────────────────────────
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\r\n")
        sb.append("{\\*\\fonttbl{\\f0\\froman\\fcharset0 Times New Roman;}}\r\n")
        sb.append("{\\*\\colortbl ;")
        for (c in allColors) {
            sb.append("\\red${(c shr 16) and 0xFF}" +
                      "\\green${(c shr 8) and 0xFF}" +
                      "\\blue${c and 0xFF};")
        }
        sb.append("}\r\n")
        sb.append("\\f0\\fs24\\pard\r\n")

        if (len == 0) { sb.append("}"); return sb.toString() }

        // ── 3. Collect all span transition points ─────────────────────────────
        // Between any two consecutive points, the active span set is constant.
        val transitions = sortedSetOf(0, len)
        for (span in ssb.getSpans(0, len, Any::class.java)) {
            val s = ssb.getSpanStart(span); val e = ssb.getSpanEnd(span)
            if (s in 0..len) transitions.add(s)
            if (e in 0..len) transitions.add(e)
        }
        val pts = transitions.toList()

        // ── 4. Active formatting at a character position ──────────────────────
        data class Fmt(
            val bold: Boolean = false, val italic: Boolean = false,
            val underline: Boolean = false, val strike: Boolean = false,
            val superscript: Boolean = false, val subscript: Boolean = false,
            val sizeHp: Int = 24,   // half-points
            val fgColor: Int = 0, val bgColor: Int = 0
        )

        fun fmtAt(pos: Int): Fmt {
            if (pos >= len) return Fmt()
            val qe = (pos + 1).coerceAtMost(len)
            var bold = false; var italic = false; var underline = false
            var strike = false; var sup = false; var sub = false
            var sizeHp = 24; var fg = 0; var bg = 0

            for (sp in ssb.getSpans(pos, qe, Any::class.java)) {
                // Confirm this span actually covers `pos` (avoids zero-length edge spans)
                if (ssb.getSpanStart(sp) > pos || ssb.getSpanEnd(sp) <= pos) continue
                when (sp) {
                    is StyleSpan -> when (sp.style) {
                        Typeface.BOLD      -> bold   = true
                        Typeface.ITALIC    -> italic = true
                        Typeface.BOLD_ITALIC -> { bold = true; italic = true }
                    }
                    is UnderlineSpan      -> underline = true
                    is StrikethroughSpan  -> strike    = true
                    is SuperscriptSpan    -> sup       = true
                    is SubscriptSpan      -> sub       = true
                    is RelativeSizeSpan   -> sizeHp    = (sp.sizeChange * 24).toInt().coerceAtLeast(8)
                    is ForegroundColorSpan -> fg       = sp.foregroundColor
                    is BackgroundColorSpan -> bg       = sp.backgroundColor
                }
            }
            return Fmt(bold, italic, underline, strike, sup, sub, sizeHp, fg, bg)
        }

        // ── 5. Walk segments, emit control-word deltas + text ─────────────────
        var prev = Fmt()

        for (idx in 0 until pts.size - 1) {
            val segStart = pts[idx]
            val segEnd   = pts[idx + 1].coerceAtMost(len)
            if (segStart >= segEnd || segStart >= len) continue

            // ── Table placeholder fast-path ───────────────────────────────────
            // If this segment is a single '\uE000' char that carries a
            // TablePlaceholderSpan, emit the verbatim RTF table block instead of
            // trying to serialise the placeholder character as text.  Close any
            // open formatting spans first so the RTF context is clean.
            if (ssb[segStart] == '\uE000') {
                val tbl = ssb.getSpans(segStart, segStart + 1, TablePlaceholderSpan::class.java)
                    .firstOrNull { ssb.getSpanStart(it) == segStart }
                if (tbl != null) {
                    // Reset any active character formatting before the table.
                    if (prev.bold)                         sb.append("\\b0 ")
                    if (prev.italic)                       sb.append("\\i0 ")
                    if (prev.underline)                    sb.append("\\ulnone ")
                    if (prev.strike)                       sb.append("\\strike0 ")
                    if (prev.superscript || prev.subscript) sb.append("\\nosupersub ")
                    if (prev.fgColor != 0)                 sb.append("\\cf0 ")
                    if (prev.bgColor != 0)                 sb.append("\\highlight0 ")
                    // Close the current paragraph, emit the raw table RTF, then
                    // reset `prev` so formatting deltas are recalculated correctly
                    // for content that follows the table.
                    sb.append("\\par\r\n")
                    sb.append(tbl.rawRtf)
                    sb.append("\r\n")
                    prev = Fmt()
                    continue
                }
            }

            val fmt = fmtAt(segStart)

            // Emit only the control words that changed since the previous segment
            if (fmt.bold        != prev.bold)        sb.append(if (fmt.bold)        "\\b "       else "\\b0 ")
            if (fmt.italic      != prev.italic)      sb.append(if (fmt.italic)      "\\i "       else "\\i0 ")
            if (fmt.underline   != prev.underline)   sb.append(if (fmt.underline)   "\\ul "      else "\\ulnone ")
            if (fmt.strike      != prev.strike)      sb.append(if (fmt.strike)      "\\strike "  else "\\strike0 ")
            if (fmt.superscript != prev.superscript) sb.append(if (fmt.superscript) "\\super "   else "\\nosupersub ")
            if (fmt.subscript   != prev.subscript)   sb.append(if (fmt.subscript)   "\\sub "     else "\\nosupersub ")
            if (fmt.sizeHp      != prev.sizeHp)      sb.append("\\fs${fmt.sizeHp} ")
            if (fmt.fgColor     != prev.fgColor)     sb.append("\\cf${colorIdx[fmt.fgColor] ?: 0} ")
            if (fmt.bgColor     != prev.bgColor)     sb.append("\\highlight${colorIdx[fmt.bgColor] ?: 0} ")

            prev = fmt

            // Emit characters for this segment
            for (pos in segStart until segEnd) {
                when (val ch = ssb[pos]) {
                    '\n'       -> sb.append("\\par\r\n")
                    '\\'       -> sb.append("\\\\")
                    '{'        -> sb.append("\\{")
                    '}'        -> sb.append("\\}")
                    '\t'       -> sb.append("\\tab ")
                    else       -> if (ch.code < 128) sb.append(ch)
                                  else sb.append("\\u${ch.code}?")
                }
            }
        }

        // Close any still-open formatting and the document group
        if (prev.bold)        sb.append("\\b0 ")
        if (prev.italic)      sb.append("\\i0 ")
        if (prev.underline)   sb.append("\\ulnone ")
        if (prev.strike)      sb.append("\\strike0 ")
        if (prev.superscript || prev.subscript) sb.append("\\nosupersub ")
        sb.append("\\par\r\n}")   // terminal paragraph + close document group

        return sb.toString()
    }

    // ── HTML wrapper ──────────────────────────────────────────────────────────

    private val TEXT_SIZES = listOf(12, 15, 18, 22)

    private fun wrapHtml(body: String): String = wrapHtml(body, TEXT_SIZES[textSizeStep])

    private fun wrapHtml(body: String, fontSizePx: Int): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          :root {
            --bg:#ffffff; --fg:#1a1a1a; --muted:#555555;
            --border:#cccccc; --code-bg:#f4f4f4;
            --blockquote-border:#4a90e2; --link:#1565c0;
            --hr:#dddddd; --h-color:#1a1a1a;
            --td-bg:#fafafa; --td-alt:#f0f0f0; --mark-bg:#fff9c4;
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:#1e1e1e; --fg:#e0e0e0; --muted:#aaaaaa;
              --border:#3a3a3a; --code-bg:#2d2d2d;
              --blockquote-border:#569cd6; --link:#4ec9b0;
              --hr:#3a3a3a; --h-color:#cccccc;
              --td-bg:#252525; --td-alt:#2a2a2a; --mark-bg:#3a3a00;
            }
          }
          * { box-sizing:border-box; margin:0; padding:0; }
          body {
            font-family:'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;
            background:var(--bg); color:var(--fg);
            font-size:${fontSizePx}px; line-height:1.75;
            padding:20px 24px 48px; word-wrap:break-word;
            -webkit-text-size-adjust:100%;
          }
          h1,h2,h3,h4,h5,h6 { color:var(--h-color); font-weight:600; margin:1.2em 0 0.5em; line-height:1.3; }
          h1 { font-size:1.9em; border-bottom:2px solid var(--border); padding-bottom:6px; }
          h2 { font-size:1.5em; border-bottom:1px solid var(--border); padding-bottom:4px; }
          h3 { font-size:1.25em; } h4 { font-size:1.1em; }
          h5 { font-size:1.0em; } h6 { font-size:0.9em; color:var(--muted); }
          p  { margin:0.5em 0; }
          p.blank { margin:0.3em 0; min-height:0.6em; }
          strong { font-weight:700; } em { font-style:italic; }
          u { text-decoration:underline; } s { text-decoration:line-through; }
          sup { vertical-align:super; font-size:0.75em; }
          sub { vertical-align:sub;   font-size:0.75em; }
          mark { background:var(--mark-bg); padding:0 2px; border-radius:2px; }
          a { color:var(--link); text-decoration:none; }
          a:hover { text-decoration:underline; }
          table { width:100%; border-collapse:collapse; margin:1em 0; font-size:0.93em; }
          td,th { border:1px solid var(--border); padding:7px 12px; text-align:left;
                  vertical-align:top; background:var(--td-bg); }
          tr:nth-child(even) td { background:var(--td-alt); }
          hr { border:none; border-top:1px solid var(--hr); margin:1.2em 0; }
          hr.page-break { border-top:2px dashed var(--border); margin:2em 0; }
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        if (_binding == null) return
        binding.progressBar.isVisible = false
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    private fun shareFile(file: File) {
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/rtf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share document"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Share failed: ${e.message}",
                Snackbar.LENGTH_LONG).show()
        }
    }
}
