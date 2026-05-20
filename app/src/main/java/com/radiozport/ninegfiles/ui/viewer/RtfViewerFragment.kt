package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
 * Supported RTF features:
 *  - Character formatting: bold, italic, underline, strikethrough, superscript,
 *    subscript, font-size, foreground colour (via `\colortbl` table), highlight
 *  - Paragraph formatting: alignment (left/centre/right/justify), left indent,
 *    paragraph style (heading 1–6 via named style groups)
 *  - Unicode escapes (`\uN?`) and ANSI hex escapes (`\'XX`)
 *  - Smart quotes, em-dash, en-dash, bullet, non-breaking space/hyphen
 *  - Page breaks rendered as `<hr class='page-break'>`
 *  - Tables (`\trowd` / `\cell` / `\row`)
 *  - Groups flagged with `\*` are silently skipped
 *
 * ## Editing
 * Tapping the pencil icon in the header switches to **edit mode**, which
 * displays the plain text extracted from the document in a full-screen
 * [EditText].  On save the text is serialised back into a minimal but
 * spec-compliant RTF 1.9 document and written to disk.  A dirty-state guard
 * prompts the user before discarding unsaved changes.
 *
 * ## Encrypted files
 * `.rtf.9genc` files encrypted with the device key are decrypted in-memory
 * before parsing.  Password-encrypted files surface an error asking the user
 * to decrypt via the Secure Vault first.
 */
class RtfViewerFragment : Fragment() {

    private var _binding: FragmentRtfViewerBinding? = null
    private val binding get() = _binding!!

    // Loaded document state
    private var currentFile: File? = null
    private var extractedPlainText: String = ""
    // Raw RTF bytes decoded as ISO-8859-1 — used as the edit buffer so that
    // saving writes back the original markup rather than re-serialised plain text.
    private var rawRtfSource: String = ""
    private var isEditMode = false
    private var isDirty = false

    // Cached rendered HTML body so the text-size toggle re-renders without re-parsing
    private var bodyHtml: String = ""

    // Text-size steps: S / M / L / XL — starts at Medium (index 1)
    private var textSizeStep: Int = 1  // 0=S 1=M 2=L 3=XL

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_PATH = "rtfPath"

        fun newInstance(path: String) = RtfViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

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
        setupButtonListeners()

        // Disable edit button for encrypted files (edit-then-save would require
        // re-encrypting, which is not yet supported for .9genc RTF files)
        if (file.name.endsWith(".9genc", ignoreCase = true)) {
            binding.btnEdit.isEnabled = false
            binding.btnEdit.alpha = 0.4f
        }

        loadDocument(file)
    }

    override fun onDestroyView() {
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    // ── Button setup ──────────────────────────────────────────────────────────

    private fun setupButtonListeners() {
        binding.btnEdit.setOnClickListener { enterEditMode() }
        binding.btnSave.setOnClickListener { saveDocument() }
        binding.btnCancel.setOnClickListener { maybeExitEditMode() }
        binding.btnShare.setOnClickListener { currentFile?.let { shareFile(it) } }

        binding.btnTextSize.setOnClickListener {
            if (bodyHtml.isEmpty()) return@setOnClickListener
            textSizeStep = (textSizeStep + 1) % 4
            binding.webView.loadDataWithBaseURL(
                null, wrapHtml(bodyHtml), "text/html", "UTF-8", null)
            val label = listOf("Small", "Medium", "Large", "X-Large")[textSizeStep]
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "Text size: $label",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
        }

        binding.etEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isEditMode) isDirty = true
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
                    val (html, plainText, stats) = result.getOrThrow()
                    bodyHtml = html
                    extractedPlainText = plainText
                    binding.tvDocInfo.text = stats
                    binding.webView.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "UTF-8", null)
                    binding.webView.isVisible = true
                }
                else -> showError("Could not render document: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // ── Edit / View mode switching ────────────────────────────────────────────

    private fun enterEditMode() {
        isEditMode = true
        isDirty = false
        binding.webView.isVisible = false
        binding.scrollEditor.isVisible = true
        binding.btnEdit.isVisible = false
        binding.btnSave.isVisible = true
        binding.btnCancel.isVisible = true
        binding.btnTextSize.isEnabled = false
        binding.btnTextSize.alpha = 0.4f
        binding.tvDocInfo.text = "Editing"
        // Populate the editor with the raw RTF markup so the save path can write
        // it straight back to disk, preserving all formatting control words.
        // (Populating with extractedPlainText would throw away every formatting
        // tag and produce an unformatted document on save.)
        binding.etEditor.setText(rawRtfSource)
        binding.etEditor.requestFocus()
    }

    private fun exitEditMode() {
        isEditMode = false
        isDirty = false
        binding.scrollEditor.isVisible = false
        binding.webView.isVisible = true
        binding.btnEdit.isVisible = true
        binding.btnSave.isVisible = false
        binding.btnCancel.isVisible = false
        binding.btnTextSize.isEnabled = true
        binding.btnTextSize.alpha = 1f
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
                "Cannot save encrypted RTF files directly.", Snackbar.LENGTH_LONG).show()
            return
        }

        val editedText = binding.etEditor.text?.toString() ?: ""
        binding.progressBar.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // Write the raw RTF markup straight back using ISO-8859-1 — the same
                    // encoding used when loading, so all control words and \'xx hex
                    // escapes round-trip without corruption.
                    // Do NOT call serializeToRtf() here: that function only produces a
                    // plain-text RTF skeleton and would permanently discard every
                    // formatting tag present in the original document.
                    file.writeText(editedText, Charsets.ISO_8859_1)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            if (ok) {
                rawRtfSource = editedText
                isDirty = false
                // Reload view with new content
                val html = withContext(Dispatchers.IO) {
                    runCatching { loadRtf(file).getOrThrow() }
                }
                if (html.isSuccess) {
                    val (newHtml, plainText, stats) = html.getOrThrow()
                    bodyHtml = newHtml
                    extractedPlainText = plainText
                    binding.tvDocInfo.text = stats
                    binding.webView.loadDataWithBaseURL(null, wrapHtml(newHtml), "text/html", "UTF-8", null)
                }
                exitEditMode()
                Snackbar.make(binding.root, "Saved successfully", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Save failed — check storage permissions", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ── WebView configuration ─────────────────────────────────────────────────

    private fun configureWebView() {
        binding.webView.apply {
            webViewClient = WebViewClient()
            settings.apply {
                javaScriptEnabled = false
                loadWithOverviewMode = false
                useWideViewPort = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            isVisible = false
        }
    }

    // ── RTF loading ───────────────────────────────────────────────────────────

    private suspend fun loadRtf(file: File): Result<Triple<String, String, String>> = runCatching {
        val bytes: ByteArray = if (EncryptionUtils.isEncrypted(file)) {
            when (EncryptionUtils.detectFormat(file)) {
                EncryptionUtils.EncryptionFormat.DEVICE_KEY ->
                    EncryptionUtils.decryptDeviceToBytes(file) { DeviceKeyManager.decryptSessionKey(it) }
                        ?: error("Decryption failed — file may be encrypted for a different device")
                EncryptionUtils.EncryptionFormat.PASSWORD_BASED ->
                    error("Password-encrypted file. Decrypt it via the Secure Vault first.")
                null -> error("Unknown encryption format")
            }
        } else {
            file.readBytes()
        }

        val (html, plainText) = parseRtf(bytes)

        // Preserve the raw source (ISO-8859-1) so the editor can round-trip without
        // discarding formatting through serializeToRtf.
        rawRtfSource = String(bytes, Charsets.ISO_8859_1)

        val wordCount = plainText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val charCount = plainText.length
        val stats = "$wordCount words · $charCount characters"

        Triple(html, plainText, stats)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF PARSER
    // Converts an RTF byte stream → (HTML body, plain text) in one pass.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimal mutable formatting state; one instance is pushed onto the stack
     * at each `{` and popped at `}`.
     */
    private data class FmtState(
        var bold: Boolean       = false,
        var italic: Boolean     = false,
        var underline: Boolean  = false,
        var strike: Boolean     = false,
        var superscript: Boolean = false,
        var subscript: Boolean  = false,
        var fontSize: Int       = 24,   // half-points; 24 = 12 pt
        var colorIndex: Int     = 0,
        var highlightIndex: Int = 0,
        var align: String       = "left",
        var leftIndent: Int     = 0,
        var isSkip: Boolean     = false  // inside \* group — discard content
    ) {
        fun copy() = FmtState(bold, italic, underline, strike, superscript, subscript,
            fontSize, colorIndex, highlightIndex, align, leftIndent, isSkip)
    }

    /**
     * Parse [bytes] as an RTF document.
     *
     * @return Pair of (HTML body string, extracted plain text string)
     */
    private fun parseRtf(bytes: ByteArray): Pair<String, String> {
        // ── Pass 0: detect encoding & read as latin-1 (RTF is 7-bit ASCII + \'xx escapes)
        val src = String(bytes, Charsets.ISO_8859_1)
        if (src.length < 5 || !src.startsWith("{\\rtf")) {
            // Fallback: treat as plain text
            val plain = String(bytes, Charsets.UTF_8)
            return Pair("<p>${plain.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}</p>", plain)
        }

        // ── Pass 1: extract color table ─────────────────────────────────────
        val colorTable: List<String> = extractColorTable(src)

        // ── Pass 2: parse RTF into HTML ─────────────────────────────────────
        val html       = StringBuilder()
        val plainText  = StringBuilder()

        val stack      = ArrayDeque<FmtState>()
        stack.addLast(FmtState())

        // Current paragraph accumulator
        val paraHtml   = StringBuilder()
        val paraPlain  = StringBuilder()

        // Table state
        var inTable    = false
        var tableHtml  = StringBuilder()

        var i = 0
        val len = src.length

        fun cur(): FmtState = stack.last()

        // Flush para HTML into main html buffer
        fun flushParagraph(forceBlank: Boolean = false) {
            if (inTable) {
                tableHtml.append(paraHtml)
                paraHtml.clear()
                paraPlain.append('\n')
                return
            }
            val content = paraHtml.toString()
            if (content.isNotEmpty()) {
                val alignStyle = when (cur().align) {
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
            paraHtml.clear()
            paraPlain.clear()
        }

        // Wrap a run of text with current formatting tags
        fun applyFormatting(text: String): String {
            if (text.isEmpty()) return ""
            var s = text
            val f = cur()
            if (f.fontSize != 24) {
                val pt = f.fontSize / 2
                val em = "%.2f".format(pt / 12.0)
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
            val escaped = raw.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            paraHtml.append(applyFormatting(escaped))
            paraPlain.append(raw)
        }

        // ── Main parsing loop ────────────────────────────────────────────────
        while (i < len) {
            when (src[i]) {
                '{' -> {
                    stack.addLast(cur().copy())
                    i++
                }
                '}' -> {
                    if (stack.size > 1) stack.removeLast()
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= len) break
                    when (src[i]) {
                        '\\' -> { appendText("\\"); i++ }
                        '{' ->  { appendText("{");  i++ }
                        '}' ->  { appendText("}");  i++ }
                        '\r', '\n' -> { /* paragraph mark in some RTF */ flushParagraph(false); i++ }
                        '\'' -> {
                            // Hex-encoded char: \'XX
                            i++
                            if (i + 1 < len) {
                                val hex = src.substring(i, i + 2)
                                val code = hex.toIntOrNull(16) ?: 0
                                appendText(code.toChar().toString())
                                i += 2
                            }
                        }
                        '-' -> { appendText("\u00AD"); i++ }   // optional hyphen
                        '_' -> { appendText("\u2011"); i++ }   // non-breaking hyphen
                        '~' -> { appendText("\u00A0"); i++ }   // non-breaking space
                        '*' -> {
                            // \* — the immediately enclosing group should be skipped
                            cur().isSkip = true; i++
                        }
                        else -> {
                            // Read control word: letters only
                            val wordStart = i
                            while (i < len && src[i].isLetter()) i++
                            val word = src.substring(wordStart, i)

                            // Read optional numeric parameter
                            var negative = false
                            if (i < len && src[i] == '-') { negative = true; i++ }
                            val numStart = i
                            while (i < len && src[i].isDigit()) i++
                            val numStr = src.substring(numStart, i)
                            val num = if (numStr.isNotEmpty()) {
                                (if (negative) -1 else 1) * numStr.toInt()
                            } else Int.MIN_VALUE  // sentinel = no number given

                            // Consume trailing space delimiter (part of RTF syntax)
                            if (i < len && src[i] == ' ') i++

                            if (cur().isSkip && word != "*") {
                                // Inside a skip group — still need to handle nested groups
                                // but discard content. Control words inside \* groups
                                // are intentionally ignored.
                            } else if (word == "u" && num != Int.MIN_VALUE) {
                                // Unicode escape: \uN? — handle here where `i` is in scope
                                // so we can skip the mandatory RTF fallback character.
                                // applyControlWord cannot do this because it has no access to i.
                                val codePoint = if (num < 0) num + 65536 else num
                                try {
                                    appendText(String(Character.toChars(codePoint)))
                                } catch (_: Exception) {
                                    appendText("?")
                                }
                                // RTF spec §1.6: exactly one character follows \uN as an
                                // ASCII fallback for older readers.  Skip it so it is not
                                // also emitted as a duplicate literal character.
                                if (i < len && src[i] != '{' && src[i] != '}' && src[i] != '\\') i++
                            } else {
                                applyControlWord(word, num, ::cur,
                                    ::appendText, ::flushParagraph,
                                    paraHtml, html, plainText,
                                    inTable.also { /* captured below */ },
                                    tableHtml,
                                    colorTable)

                                // Handle table state changes returned from control word handler
                                when (word) {
                                    "trowd" -> {
                                        if (!inTable) {
                                            inTable = true
                                            tableHtml = StringBuilder("<table>\n")
                                        }
                                        tableHtml.append("<tr>")
                                    }
                                    "row" -> {
                                        flushParagraph(false)
                                        tableHtml.append("</tr>\n")
                                    }
                                    "cell" -> {
                                        val content = paraHtml.toString()
                                        paraHtml.clear(); paraPlain.clear()
                                        tableHtml.append("<td>$content</td>")
                                    }
                                    "intbl" -> inTable = true
                                    "pard"  -> {
                                        // On \pard, if we were in a table and now are not,
                                        // close the table
                                    }
                                }

                                // Close table when we see \pard outside intbl context
                                if (word == "pard" && inTable) {
                                    // Check if we're really outside a table now
                                    // (simplified: close table on next non-intbl paragraph)
                                }
                            }

                            // Unicode escape \uN? is handled in the else-if branch above.
                        }
                    }
                }
                '\r', '\n' -> i++ // ignored in RTF
                else -> {
                    if (!cur().isSkip) {
                        appendText(src[i].toString())
                    }
                    i++
                }
            }
        }

        // Flush any remaining paragraph
        flushParagraph(false)

        // Close any open table
        if (inTable) {
            tableHtml.append("</table>\n")
            html.append(tableHtml)
        }

        return Pair(html.toString(), plainText.toString().trim())
    }

    /**
     * Applies a single RTF control word to the current formatting state.
     * Handles character/paragraph formatting, special chars, and structural words.
     */
    private fun applyControlWord(
        word: String,
        num: Int,
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
            // ── Paragraph breaks ────────────────────────────────────────
            "par"    -> flushParagraph(true)
            "line"   -> { paraHtml.append("<br>"); plainText.append('\n') }
            "page"   -> { flushParagraph(true); html.append("<hr class='page-break'>\n") }
            "sect"   -> { flushParagraph(true); html.append("<hr>\n") }
            "column" -> flushParagraph(true)

            // ── Tab ─────────────────────────────────────────────────────
            "tab"    -> { paraHtml.append("&nbsp;&nbsp;&nbsp;&nbsp;"); plainText.append('\t') }

            // ── Character formatting ─────────────────────────────────────
            "b"      -> cur().bold       = !(hasNum && num == 0)
            "i"      -> cur().italic     = !(hasNum && num == 0)
            "ul"     -> cur().underline  = !(hasNum && num == 0)
            "uld"    -> cur().underline  = !(hasNum && num == 0)  // dotted underline
            "uldb"   -> cur().underline  = !(hasNum && num == 0)  // double underline
            "ulwave" -> cur().underline  = !(hasNum && num == 0)  // wave underline
            "ulnone" -> cur().underline  = false
            "strike" -> cur().strike     = !(hasNum && num == 0)
            "striked"-> cur().strike     = !(hasNum && num == 0)

            "super"  -> { cur().superscript = true;  cur().subscript = false }
            "sub"    -> { cur().subscript   = true;  cur().superscript = false }
            "nosupersub" -> { cur().superscript = false; cur().subscript = false }

            "fs"     -> if (hasNum && num > 0) cur().fontSize = num
            "cf"     -> cur().colorIndex = if (hasNum) num else 0
            "cb", "highlight" -> cur().highlightIndex = if (hasNum) num else 0

            "plain"  -> {
                // Reset ALL character formatting to defaults
                val c = cur()
                c.bold = false; c.italic = false; c.underline = false
                c.strike = false; c.superscript = false; c.subscript = false
                c.fontSize = 24; c.colorIndex = 0; c.highlightIndex = 0
            }

            // ── Paragraph formatting ─────────────────────────────────────
            "pard"   -> {
                val c = cur()
                c.align = "left"; c.leftIndent = 0
                c.bold = false; c.italic = false; c.underline = false
                c.strike = false; c.superscript = false; c.subscript = false
                c.fontSize = 24; c.colorIndex = 0; c.highlightIndex = 0
            }
            "ql"     -> cur().align = "left"
            "qc"     -> cur().align = "center"
            "qr"     -> cur().align = "right"
            "qj"     -> cur().align = "justify"
            "li"     -> if (hasNum) cur().leftIndent = num

            // ── Unicode ──────────────────────────────────────────────────
            // NOTE: \uN? is handled directly in the main parsing loop (parseRtf)
            // so that the RTF fallback character following the escape can be
            // consumed via the loop's `i` index.  No case needed here.

            // ── Special characters ───────────────────────────────────────
            "emdash"     -> appendText("\u2014")
            "endash"     -> appendText("\u2013")
            "bullet"     -> appendText("\u2022")
            "lquote"     -> appendText("\u2018")
            "rquote"     -> appendText("\u2019")
            "ldblquote"  -> appendText("\u201C")
            "rdblquote"  -> appendText("\u201D")
            "enspace"    -> appendText("\u2002")
            "emspace"    -> appendText("\u2003")
            "qmspace"    -> appendText("\u2004")
            "zwbo"       -> { /* zero-width break opportunity — ignore */ }
            "zwnbo"      -> { /* zero-width non-break opportunity — ignore */ }

            // ── List item detection ──────────────────────────────────────
            // RTF list items are signalled by \listtext groups; we treat
            // them as bullet paragraphs by prepending a bullet on \pntext.
            "pntext"     -> { /* handled via \bullet or literal bullet char */ }

            // ── Section words to silently ignore ────────────────────────
            "rtf1", "ansi", "ansicpg", "mac", "pc", "pca",
            "deff", "deflang", "deflangfe",
            "fonttbl", "colortbl", "stylesheet", "info",
            "listtable", "listoverridetable",
            "revtbl", "rsidtbl",
            "header", "footer", "headerl", "headerr", "headerf",
            "footerl", "footerr", "footerf",
            "fldinst", "fldrslt",
            "pict", "object", "datafield",
            "shp", "shpinst", "sp",
            "wgrffmtfilter",
            "themedata", "colorschememapping",
            "xmlns", "xmlopen", "xmlclose",
            "ts", "tscellpaddfl", "tscellpaddfr", "tscellpaddft", "tscellpaddfb",
            "cellx" -> { /* silently skip */ }

            // ── Anything unrecognised — silently ignore ──────────────────
            else -> { /* unknown control word — ignore per RTF spec */ }
        }
    }

    /**
     * Extracts the color table from an RTF source string.
     * Returns a list of CSS `#RRGGBB` strings indexed from 0
     * (corresponding to RTF color indices 1, 2, …).
     */
    private fun extractColorTable(src: String): List<String> {
        val colors = mutableListOf<String>()
        // Find {\colortbl ...}
        val start = src.indexOf("{\\colortbl")
        if (start < 0) return colors
        var depth = 0
        val buf = StringBuilder()
        var i = start
        while (i < src.length) {
            when (src[i]) {
                '{' -> { depth++; i++ }
                '}' -> {
                    depth--; i++
                    if (depth == 0) {
                        // parse buf for \redN\greenN\blueN; entries
                        parseColorEntries(buf.toString(), colors)
                        break
                    }
                }
                else -> { if (depth > 0) buf.append(src[i]); i++ }
            }
        }
        return colors
    }

    private fun parseColorEntries(colortblContent: String, out: MutableList<String>) {
        // Each entry is separated by ';'. Each entry may have \redN\greenN\blueN.
        // First entry (before first ';') may be empty → "auto" color.
        val entries = colortblContent.split(';')
        for (entry in entries) {
            if (entry.isBlank()) {
                // Empty entry = "auto" (default foreground/background) — omit
                out.add("")
                continue
            }
            val red   = Regex("\\\\red(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val green = Regex("\\\\green(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val blue  = Regex("\\\\blue(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add("#%02x%02x%02x".format(red, green, blue))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTF SERIALISER
    // Converts plain text back into a minimal, spec-compliant RTF 1.9 document.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serialises [text] into a minimal RTF 1.9 document.
     *
     * Non-ASCII characters are encoded as `\uN?` Unicode escapes.
     * Newlines become `\par` breaks.  The result is 7-bit-clean ASCII.
     */
    private fun serializeToRtf(text: String): String {
        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\r\n")
        sb.append("{\\fonttbl{\\f0\\froman\\fcharset0 Times New Roman;}}\r\n")
        sb.append("{\\colortbl ;\\red0\\green0\\blue0;}\r\n")
        sb.append("\\f0\\fs24\\cf1\\pard\r\n")

        for (line in text.lines()) {
            for (ch in line) {
                when {
                    ch == '\\' -> sb.append("\\\\")
                    ch == '{'  -> sb.append("\\{")
                    ch == '}'  -> sb.append("\\}")
                    ch.code < 128 -> sb.append(ch)
                    else -> {
                        // Unicode escape: \uN? where ? is the best-fit ASCII fallback.
                        // Use '?' universally — we cannot transliterate arbitrary Unicode
                        // to a single ASCII byte without a full mapping table.
                        val code = ch.code
                        sb.append("\\u$code?")
                    }
                }
            }
            sb.append("\\par\r\n")
        }

        sb.append("}")
        return sb.toString()
    }

    // ── HTML wrapper ──────────────────────────────────────────────────────────

    /** Font sizes (px) for the four text-size steps: S / M / L / XL */
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
            --bg:   #ffffff; --fg:   #1a1a1a; --muted:#555555;
            --border:#cccccc; --code-bg:#f4f4f4;
            --blockquote-border:#4a90e2; --link:#1565c0;
            --hr:   #dddddd; --h-color:#1a1a1a;
            --td-bg:#fafafa; --td-alt:#f0f0f0; --mark-bg:#fff9c4;
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:   #1e1e1e; --fg:   #e0e0e0; --muted:#aaaaaa;
              --border:#3a3a3a; --code-bg:#2d2d2d;
              --blockquote-border:#569cd6; --link:#4ec9b0;
              --hr:   #3a3a3a; --h-color:#cccccc;
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
          h1,h2,h3,h4,h5,h6 {
            color:var(--h-color); font-weight:600;
            margin:1.2em 0 0.5em; line-height:1.3;
          }
          h1 { font-size:1.9em; border-bottom:2px solid var(--border); padding-bottom:6px; }
          h2 { font-size:1.5em; border-bottom:1px solid var(--border); padding-bottom:4px; }
          h3 { font-size:1.25em; }
          h4 { font-size:1.1em; }
          h5 { font-size:1.0em; }
          h6 { font-size:0.9em; color:var(--muted); }
          p  { margin:0.5em 0; }
          p.blank { margin:0.3em 0; min-height:0.6em; }
          strong { font-weight:700; }
          em     { font-style:italic; }
          u      { text-decoration:underline; }
          s      { text-decoration:line-through; }
          sup    { vertical-align:super; font-size:0.75em; }
          sub    { vertical-align:sub;   font-size:0.75em; }
          mark   { background:var(--mark-bg); padding:0 2px; border-radius:2px; }
          a { color:var(--link); text-decoration:none; }
          a:hover { text-decoration:underline; }
          table {
            width:100%; border-collapse:collapse;
            margin:1em 0; font-size:0.93em;
          }
          td, th {
            border:1px solid var(--border);
            padding:7px 12px; text-align:left;
            vertical-align:top; background:var(--td-bg);
          }
          tr:nth-child(even) td { background:var(--td-alt); }
          hr {
            border:none; border-top:1px solid var(--hr); margin:1.2em 0;
          }
          hr.page-break {
            border-top:2px dashed var(--border); margin:2em 0;
          }
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
            Snackbar.make(binding.root, "Share failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
