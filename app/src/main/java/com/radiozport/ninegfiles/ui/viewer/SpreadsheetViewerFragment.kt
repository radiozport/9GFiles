package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.util.Xml
import android.view.*
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.evrencoskun.tableview.adapter.AbstractTableAdapter
import com.evrencoskun.tableview.adapter.recyclerview.holder.AbstractViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.databinding.FragmentSpreadsheetViewerBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * In-app viewer for spreadsheet documents.
 *
 * Supported formats:
 *  - `.xlsx` / `.xlsm` — Office Open XML; parsed natively via ZipInputStream + XmlPullParser.
 *  - `.xls`            — Legacy BIFF binary; parsed via Apache POI HSSF.
 *  - `.ods`            — OpenDocument Spreadsheet; parsed natively via ZipInputStream + XmlPullParser.
 *  - `.csv`            — Plain-text; parsed inline with RFC-4180 field splitting.
 *
 * Encrypted `.9genc` files (device-key only) are decrypted in-memory before parsing.
 *
 * Rendering: evrencoskun/TableView (v0.8.9.4) — native RecyclerView-based grid with
 * frozen column/row headers, alternating row stripes, and scalable text size.
 * Replaces the previous WebView/HTML approach; zero web-security surface, smoother
 * scrolling, and correct theme (light/dark) without any JS evaluation.
 */
class SpreadsheetViewerFragment : Fragment() {

    private var _binding: FragmentSpreadsheetViewerBinding? = null
    private val binding get() = _binding!!

    private var spreadsheetData: SpreadsheetData? = null
    private var currentSheetIndex: Int = 0
    private var textSizeStep: Int = 1  // 0=S 1=M 2=L 3=XL

    private lateinit var tableAdapter: SpreadsheetTableAdapter

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {
        private const val ARG_PATH = "spreadsheetPath"
        private const val MAX_ROWS = 500
        private const val MAX_COLS = 50
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        /** Font sizes (sp) cycled by the text-size button: S / M / L / XL */
        private val TEXT_SP = floatArrayOf(11f, 13f, 15f, 18f)

        fun newInstance(path: String) = SpreadsheetViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    private data class SheetData(val name: String, val rows: List<List<String>>)
    private data class SpreadsheetData(val sheets: List<SheetData>)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpreadsheetViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run {
            showError("No file path provided"); return
        }
        val file = File(path)
        if (!file.exists()) { showError("File not found"); return }

        binding.tvFileName.text = file.name.removeSuffix(".9genc").removeSuffix(".9GENC")
        binding.tvDocInfo.text   = "Loading…"
        binding.progressBar.isVisible = true
        binding.tableView.isVisible = false
        binding.sheetTabsScroll.isVisible = false

        tableAdapter = SpreadsheetTableAdapter()
        binding.tableView.setAdapter(tableAdapter)
        setupButtons(file)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadSpreadsheet(file) }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            result.fold(
                onSuccess = { data ->
                    spreadsheetData = data
                    buildSheetTabs(data)
                    renderSheet(0)
                },
                onFailure = { e ->
                    showError("Could not open file: ${e.message}")
                }
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Button wiring ─────────────────────────────────────────────────────────

    private fun setupButtons(file: File) {
        binding.btnTextSize.setOnClickListener {
            textSizeStep = (textSizeStep + 1) % 4
            renderSheet(currentSheetIndex)
        }
        binding.btnShare.setOnClickListener { shareFile(file) }
    }

    private fun shareFile(file: File) {
        val srcFile = if (file.name.endsWith(".9genc", ignoreCase = true)) {
            // Share the raw encrypted file; user can send it to another device
            file
        } else file
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", srcFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share…"))
        } catch (_: Exception) {
            Snackbar.make(binding.root, "Could not share file.", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Sheet tabs ────────────────────────────────────────────────────────────

    private fun buildSheetTabs(data: SpreadsheetData) {
        binding.sheetTabsContainer.removeAllViews()
        if (data.sheets.size <= 1) {
            binding.sheetTabsScroll.isVisible = false
            return
        }
        binding.sheetTabsScroll.isVisible = true
        val ctx = requireContext()
        data.sheets.forEachIndexed { idx, sheet ->
            val btn = MaterialButton(
                ctx,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text           = sheet.name
                isAllCaps      = false
                setPadding(32, 0, 32, 0)
                setOnClickListener { renderSheet(idx) }
            }
            binding.sheetTabsContainer.addView(btn)
        }
        highlightTab(0)
    }

    private fun highlightTab(idx: Int) {
        val container = binding.sheetTabsContainer
        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? MaterialButton ?: continue
            btn.isSelected = (i == idx)
            // Material outlined button: selected state driven by the color state list
        }
    }

    // ── Sheet rendering ───────────────────────────────────────────────────────

    private fun renderSheet(idx: Int) {
        val data = spreadsheetData ?: return
        if (idx < 0 || idx >= data.sheets.size) return
        currentSheetIndex = idx
        highlightTab(idx)

        val sheet       = data.sheets[idx]
        val totalRows   = sheet.rows.size
        val capped      = totalRows > MAX_ROWS
        val displayRows = if (capped) sheet.rows.take(MAX_ROWS) else sheet.rows
        val maxCols     = displayRows.maxOfOrNull { it.size } ?: 0
        val colCount    = minOf(maxCols, MAX_COLS)

        binding.tvDocInfo.text = buildString {
            append("Sheet ${idx + 1} / ${data.sheets.size}")
            if (totalRows > 0) append("  ·  $totalRows rows")
            if (maxCols  > 0)  append("  ×  $maxCols cols")
            if (capped)        append("  (first $MAX_ROWS shown)")
            if (maxCols > MAX_COLS) append("  (first $MAX_COLS cols shown)")
        }

        if (displayRows.isEmpty()) {
            tableAdapter.setAllItems(emptyList(), emptyList(), emptyList())
            binding.tableView.isVisible = true
            return
        }

        // Row 0 → frozen column headers
        val headerRow     = displayRows.first()
        val columnHeaders = List(colCount) { j -> if (j < headerRow.size) headerRow[j] else "" }

        // Rows 1..n → data; row numbers become the frozen row header
        val dataRows   = displayRows.drop(1)
        val rowHeaders = dataRows.indices.map { (it + 1).toString() }
        val cells      = dataRows.map { row ->
            List(colCount) { j -> if (j < row.size) row[j] else "" }
        }

        tableAdapter.setAllItems(columnHeaders, rowHeaders, cells)
        binding.tableView.isVisible = true
    }

    // ── TableView adapter ─────────────────────────────────────────────────────
    //
    // All three regions (corner, column headers, row headers, cells) are built
    // from plain TextViews created programmatically — no extra layout files needed.
    // Text size is re-applied in every onBind* so cycling the size button just
    // re-calls renderSheet(), which triggers a full rebind at the new sp value.

    private inner class SpreadsheetTableAdapter
        : AbstractTableAdapter<String, String, String>() {

        // ── Helpers ───────────────────────────────────────────────────────────

        private fun sp()  = TEXT_SP[textSizeStep]
        private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

        // Extension so every view resolves attributes from its OWN context.
        // Using requireContext() here caused mixed light/dark resolution when the
        // TableView's internal RecyclerView contexts differed from the Fragment's
        // context — producing white backgrounds with near-invisible light text.
        private fun android.content.Context.attr(attrRes: Int): Int {
            val tv = TypedValue()
            theme.resolveAttribute(attrRes, tv, true)
            return tv.data
        }

        /** Bold, surface-variant background — used for column/row headers and corner. */
        private fun headerView(context: android.content.Context): TextView =
            TextView(context).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp())
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                gravity  = Gravity.CENTER_VERTICAL or Gravity.START
                setBackgroundColor(context.attr(com.google.android.material.R.attr.colorSurfaceVariant))
                setTextColor(context.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }

        /** Normal-weight cell — background is set per-row in onBind for stripe effect. */
        private fun cellView(context: android.content.Context): TextView =
            TextView(context).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp())
                maxLines = 1
                gravity  = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(context.attr(com.google.android.material.R.attr.colorOnSurface))
            }

        // ── Cell ──────────────────────────────────────────────────────────────

        override fun onCreateCellViewHolder(parent: ViewGroup, viewType: Int): AbstractViewHolder =
            object : AbstractViewHolder(cellView(parent.context)) {}

        override fun onBindCellViewHolder(
            holder: AbstractViewHolder, value: String?, col: Int, row: Int
        ) = (holder.itemView as TextView).run {
            val ctx = holder.itemView.context
            text = value ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp())
            setBackgroundColor(
                if (row % 2 == 0) ctx.attr(com.google.android.material.R.attr.colorSurface)
                else              ctx.attr(com.google.android.material.R.attr.colorSurfaceVariant)
            )
        }

        // ── Column header ─────────────────────────────────────────────────────

        override fun onCreateColumnHeaderViewHolder(parent: ViewGroup, viewType: Int): AbstractViewHolder =
            object : AbstractViewHolder(headerView(parent.context)) {}

        override fun onBindColumnHeaderViewHolder(
            holder: AbstractViewHolder, value: String?, col: Int
        ) = (holder.itemView as TextView).run {
            text = value ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp())
        }

        // ── Row header ────────────────────────────────────────────────────────

        override fun onCreateRowHeaderViewHolder(parent: ViewGroup, viewType: Int): AbstractViewHolder =
            object : AbstractViewHolder(
                headerView(parent.context).apply {
                    minWidth = dp(44)
                    gravity  = Gravity.CENTER
                }
            ) {}

        override fun onBindRowHeaderViewHolder(
            holder: AbstractViewHolder, value: String?, row: Int
        ) = (holder.itemView as TextView).run {
            text = value ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp())
        }

        // ── Corner cell ───────────────────────────────────────────────────────

        override fun onCreateCornerView(parent: ViewGroup): android.view.View =
            headerView(parent.context).apply {
                text     = "#"
                minWidth = dp(44)
                gravity  = Gravity.CENTER
            }

        // ── View types (single type per region) ───────────────────────────────

        override fun getCellItemViewType(position: Int)         = 0
        override fun getColumnHeaderItemViewType(position: Int) = 0
        override fun getRowHeaderItemViewType(position: Int)    = 0
    }

    // ── Error helper ──────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        binding.progressBar.isVisible = false
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    // ── Loading / decryption dispatch ─────────────────────────────────────────

    private suspend fun loadSpreadsheet(file: File): Result<SpreadsheetData> {
        // Resolve extension first (needed for the parser switch below)
        val is9genc  = file.name.endsWith(".9genc", ignoreCase = true)
        val innerExt = if (is9genc) EncryptionUtils.innerExtension(file) else file.extension.lowercase()

        // decryptDeviceToBytes is suspend — call it directly here, NOT inside a lambda,
        // because runCatching's block type is () -> R (non-suspend).
        val bytes: ByteArray = if (is9genc) {
            EncryptionUtils.decryptDeviceToBytes(
                source              = file,
                sessionKeyDecryptor = { DeviceKeyManager.decryptSessionKey(it) }
            ) ?: return Result.failure(
                IllegalStateException("Decryption failed — file may be encrypted for a different device.")
            )
        } else {
            file.readBytes()
        }

        // Only the synchronous parsing goes inside runCatching
        return runCatching {
            when (innerExt) {
                "xlsx", "xlsm" -> parseXlsx(bytes.inputStream())
                "xls"          -> throw UnsupportedOperationException(
                "Legacy .xls format cannot be previewed in-app.\n\n"
                    + "Tap the share button ↗ to open with a spreadsheet app."
            )
                "ods"          -> parseOds(bytes.inputStream())
                "csv"          -> parseCsv(bytes.toString(Charsets.UTF_8))
                else           -> throw IllegalArgumentException("Unsupported format: .$innerExt")
            }
        }
    }

    // ── XLSX parser (ZipInputStream + XmlPullParser) ──────────────────────────

    private fun parseXlsx(input: InputStream): SpreadsheetData {
        // Step 1: collect needed zip entries
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val n = entry.name.trimStart('/')
                if (n == "xl/workbook.xml" ||
                    n == "xl/_rels/workbook.xml.rels" ||
                    n == "xl/sharedStrings.xml" ||
                    (n.startsWith("xl/worksheets/") && n.endsWith(".xml"))) {
                    entries[n] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Step 2: shared strings
        val sharedStrings: List<String> = entries["xl/sharedStrings.xml"]
            ?.let { parseXlsxSharedStrings(it) } ?: emptyList()

        // Step 3: sheet names + rIds from workbook.xml
        val sheetOrder = mutableListOf<Pair<String, String>>() // name to rId
        entries["xl/workbook.xml"]?.let { wb ->
            val p = xmlParser(wb)
            var evt = p.eventType
            while (evt != XmlPullParser.END_DOCUMENT) {
                if (evt == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = p.getAttributeValue(null, "name") ?: "Sheet"
                    val rId  = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                        ?: p.getAttributeValue(null, "r:id") ?: ""
                    sheetOrder.add(name to rId)
                }
                evt = p.next()
            }
        }

        // Step 4: rId → file path from workbook.xml.rels
        val rIdToPath = mutableMapOf<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { rels ->
            val p = xmlParser(rels)
            var evt = p.eventType
            while (evt != XmlPullParser.END_DOCUMENT) {
                if (evt == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id     = p.getAttributeValue(null, "Id") ?: ""
                    val target = p.getAttributeValue(null, "Target") ?: ""
                    rIdToPath[id] = "xl/$target".replace("xl/xl/", "xl/")
                }
                evt = p.next()
            }
        }

        // Step 5: parse each sheet in order
        val sheets = sheetOrder.mapNotNull { (name, rId) ->
            val path  = rIdToPath[rId] ?: return@mapNotNull null
            val bytes = entries[path]   ?: return@mapNotNull null
            SheetData(name, parseXlsxSheet(bytes, sharedStrings))
        }

        // Fallback: if rels parsing failed, just read whatever sheet files we found
        val result = if (sheets.isNotEmpty()) sheets else {
            entries.filterKeys { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .entries.sortedBy { it.key }
                .mapIndexed { i, (_, bytes) ->
                    SheetData("Sheet ${i + 1}", parseXlsxSheet(bytes, sharedStrings))
                }
        }
        return SpreadsheetData(result.ifEmpty { listOf(SheetData("Sheet 1", emptyList())) })
    }

    private fun parseXlsxSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val p    = xmlParser(bytes)
        var evt  = p.eventType
        var inSi = false
        var sb   = StringBuilder()
        while (evt != XmlPullParser.END_DOCUMENT) {
            when {
                evt == XmlPullParser.START_TAG && p.name == "si" -> { inSi = true; sb.clear() }
                evt == XmlPullParser.START_TAG && inSi && p.name == "t" -> {
                    evt = p.next()
                    if (evt == XmlPullParser.TEXT) sb.append(p.text)
                }
                evt == XmlPullParser.END_TAG && p.name == "si" -> {
                    list.add(sb.toString())
                    inSi = false
                }
            }
            evt = p.next()
        }
        return list
    }

    private fun parseXlsxSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows     = mutableListOf<List<String>>()
        val p        = xmlParser(bytes)
        var evt      = p.eventType
        var curRow   = mutableListOf<String>()
        var curType  = ""
        var curRef   = ""
        var inV      = false
        var vText    = StringBuilder()
        // Track formula text so we can display it when no cached <v> value is present
        var inF           = false
        var formulaText   = StringBuilder()
        var cellValueAdded = false

        while (evt != XmlPullParser.END_DOCUMENT) {
            when {
                evt == XmlPullParser.START_TAG && p.name == "row" -> {
                    curRow = mutableListOf()
                }
                evt == XmlPullParser.START_TAG && p.name == "c" -> {
                    curType        = p.getAttributeValue(null, "t") ?: ""
                    curRef         = p.getAttributeValue(null, "r") ?: ""
                    cellValueAdded = false
                    formulaText.clear()
                }
                // Capture formula text so it can serve as a fallback display value
                evt == XmlPullParser.START_TAG && p.name == "f" -> {
                    inF = true; formulaText.clear()
                }
                evt == XmlPullParser.TEXT && inF -> {
                    formulaText.append(p.text)
                }
                evt == XmlPullParser.END_TAG && p.name == "f" -> {
                    inF = false
                }
                evt == XmlPullParser.START_TAG && p.name == "v" -> {
                    inV = true; vText.clear()
                }
                evt == XmlPullParser.START_TAG && p.name == "t" && curType == "inlineStr" -> {
                    inV = true; vText.clear()
                }
                evt == XmlPullParser.TEXT && inV -> {
                    vText.append(p.text)
                }
                evt == XmlPullParser.END_TAG && (p.name == "v" || p.name == "t") && inV -> {
                    inV = false
                    // Determine column index from ref (e.g. "C5" → col 2)
                    val (colIdx, _) = parseCellRef(curRef)
                    // Pad to colIdx
                    while (curRow.size < colIdx) curRow.add("")
                    val rawVal = vText.toString()
                    val cellVal = when (curType) {
                        "s"         -> sharedStrings.getOrElse(rawVal.toIntOrNull() ?: -1) { rawVal }
                        "b"         -> if (rawVal == "1") "TRUE" else "FALSE"
                        "e"         -> rawVal // error
                        "str", "inlineStr" -> rawVal
                        else        -> formatNumeric(rawVal)
                    }
                    if (curRow.size == colIdx) curRow.add(cellVal)
                    else if (colIdx < curRow.size) curRow[colIdx] = cellVal
                    cellValueAdded = true
                }
                // Fallback: cell has a formula but no cached <v> — display the formula expression
                evt == XmlPullParser.END_TAG && p.name == "c" -> {
                    if (!cellValueAdded && formulaText.isNotEmpty()) {
                        val (colIdx, _) = parseCellRef(curRef)
                        while (curRow.size < colIdx) curRow.add("")
                        val cellVal = "=${formulaText}"
                        if (curRow.size == colIdx) curRow.add(cellVal)
                        else if (colIdx < curRow.size) curRow[colIdx] = cellVal
                    }
                    cellValueAdded = false
                    formulaText.clear()
                    inF = false
                }
                evt == XmlPullParser.END_TAG && p.name == "row" -> {
                    if (curRow.isNotEmpty()) {
                        if (rows.size < MAX_ROWS) rows.add(curRow)
                        else return rows // cap reached
                    }
                    curRow  = mutableListOf()
                    curType = ""
                    curRef  = ""
                }
            }
            evt = p.next()
        }
        return rows
    }

    /** "C5" → (2, 4); "A1" → (0, 0) */
    private fun parseCellRef(ref: String): Pair<Int, Int> {
        var col = 0; var row = 0; var i = 0
        while (i < ref.length && ref[i].isLetter()) { col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
        row = ref.substring(i).toIntOrNull()?.minus(1) ?: 0
        return col - 1 to row
    }

    private fun formatNumeric(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d == kotlin.math.floor(d) && !raw.contains('E') && !raw.contains('e'))
            d.toLong().toString()
        else raw
    }

    // ── ODS parser (ZipInputStream + XmlPullParser on content.xml) ───────────

    private fun parseOds(input: InputStream): SpreadsheetData {
        var contentXml: ByteArray? = null
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") { contentXml = zis.readBytes(); break }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
        val xml = contentXml ?: return SpreadsheetData(listOf(SheetData("Sheet 1", emptyList())))

        val sheets   = mutableListOf<SheetData>()
        val p        = xmlParser(xml)
        var evt      = p.eventType
        var curName  = ""
        var curRows  = mutableListOf<List<String>>()
        var curRow   = mutableListOf<String>()
        var curCells = mutableListOf<String>()
        var inTable  = false
        var inRow    = false
        var inCell   = false
        var cellRepeated    = 1
        var cellTextSb      = StringBuilder()
        // Attribute-based fallback value for formula/calculated cells that omit <text:p>
        var cellFallbackVal = ""
        var inTextP  = false

        // Namespace URIs used in ODS
        val nsOffice = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        val nsTable  = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"

        while (evt != XmlPullParser.END_DOCUMENT) {
            val tag = p.name ?: ""
            when {
                // ── sheet start
                evt == XmlPullParser.START_TAG && tag == "table" -> {
                    curName = p.getAttributeValue(nsTable, "name") ?: ""
                    curRows = mutableListOf(); inTable = true
                }
                evt == XmlPullParser.END_TAG && tag == "table" && inTable -> {
                    sheets.add(SheetData(curName.ifEmpty { "Sheet ${sheets.size + 1}" }, curRows))
                    inTable = false
                }
                // ── row
                evt == XmlPullParser.START_TAG && tag == "table-row" && inTable -> {
                    curRow = mutableListOf(); inRow = true
                }
                evt == XmlPullParser.END_TAG && tag == "table-row" && inRow -> {
                    // Drop trailing empty cells
                    val trimmed = curRow.dropLastWhile { it.isEmpty() }
                    if (trimmed.isNotEmpty() && curRows.size < MAX_ROWS) curRows.add(trimmed)
                    inRow = false
                }
                // ── cell
                evt == XmlPullParser.START_TAG && tag == "table-cell" && inRow -> {
                    cellRepeated = p.getAttributeValue(nsTable, "number-columns-repeated")
                        ?.toIntOrNull() ?: 1
                    // Read cached value attributes — used as fallback when <text:p> is absent,
                    // which is common for calculated/formula cells in many ODS writers.
                    val valueType = p.getAttributeValue(nsOffice, "value-type") ?: ""
                    cellFallbackVal = when (valueType) {
                        "float", "currency", "percentage" ->
                            p.getAttributeValue(nsOffice, "value")
                                ?.let { formatNumeric(it) } ?: ""
                        "string"  -> p.getAttributeValue(nsOffice, "string-value") ?: ""
                        "boolean" -> when (p.getAttributeValue(nsOffice, "boolean-value")) {
                            "true"  -> "TRUE"
                            "false" -> "FALSE"
                            else    -> ""
                        }
                        "date"     -> p.getAttributeValue(nsOffice, "date-value") ?: ""
                        "time"     -> p.getAttributeValue(nsOffice, "time-value") ?: ""
                        else       -> ""
                    }
                    cellTextSb.clear(); inCell = true
                }
                evt == XmlPullParser.END_TAG && tag == "table-cell" && inCell -> {
                    // Prefer the formatted display text; fall back to the raw attribute value
                    // for formula cells whose <text:p> was omitted by the producer.
                    val v = cellTextSb.toString().trim().ifEmpty { cellFallbackVal }
                    repeat(minOf(cellRepeated, MAX_COLS - curRow.size)) { curRow.add(v) }
                    inCell = false
                    cellFallbackVal = ""
                }
                // ── cell text
                evt == XmlPullParser.START_TAG && tag == "p" && inCell -> { inTextP = true }
                evt == XmlPullParser.END_TAG   && tag == "p" && inCell -> { inTextP = false }
                evt == XmlPullParser.TEXT && inTextP -> { cellTextSb.append(p.text) }
            }
            evt = p.next()
        }
        return SpreadsheetData(sheets.ifEmpty { listOf(SheetData("Sheet 1", emptyList())) })
    }

    // ── CSV parser ────────────────────────────────────────────────────────────

    private fun parseCsv(text: String): SpreadsheetData {
        val lines = text.lines().filter { it.isNotBlank() }
        val rows  = lines.take(MAX_ROWS).map { parseCsvRow(it) }
        return SpreadsheetData(listOf(SheetData("CSV", rows)))
    }

    /** Splits a single CSV line respecting RFC-4180 double-quoted fields. */
    private fun parseCsvRow(line: String): List<String> {
        val cols  = mutableListOf<String>()
        val sb    = StringBuilder()
        var inQ   = false
        var i     = 0
        val delim = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQ                           -> inQ = true
                c == '"' && inQ && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' && inQ                            -> inQ = false
                c == delim && !inQ                         -> { cols.add(sb.toString()); sb.clear() }
                else                                       -> sb.append(c)
            }
            i++
        }
        cols.add(sb.toString())
        return cols.take(MAX_COLS)
    }

    // ── XML helper ────────────────────────────────────────────────────────────

    private fun xmlParser(bytes: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(bytes.inputStream(), "UTF-8")
        p.nextToken()   // move to first event
        return p
    }
}
