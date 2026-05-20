package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.findNavController
import com.cherry.lib.doc.DocViewerActivity
import com.cherry.lib.doc.bean.DocEngine
import com.cherry.lib.doc.bean.DocSourceType
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.databinding.FragmentPresentationViewerBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Presentation viewer backed by Victor2018/DocViewer (JitPack).
 *
 * The library ships its own Activity (DocViewerActivity) which is launched
 * directly via its static helper using DocSourceType.PATH + DocEngine.INTERNAL
 * so rendering is fully offline.
 *
 * Flow:
 *   • Shows filename + spinner while preparing the file.
 *   • Decrypts .9genc files to cacheDir/9genc_tmp/<name>.pptx (IO thread).
 *   • Hands off to DocViewerActivity with source type PATH and INTERNAL engine.
 *   • Pops itself from the nav back stack so Back in DocViewerActivity returns
 *     directly to the file browser.
 *   • Falls back to a system ACTION_VIEW chooser if DocViewerActivity throws.
 */
class PresentationViewerFragment : Fragment() {

    private var _binding: FragmentPresentationViewerBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_PATH     = "pptxPath"
        private const val TMP_DIR_NAME = "9genc_tmp"

        fun newInstance(path: String) = PresentationViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPresentationViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rawPath = arguments?.getString(ARG_PATH) ?: run {
            showError("No file path provided"); return
        }
        val sourceFile = File(rawPath)
        if (!sourceFile.exists()) { showError("File not found: $rawPath"); return }

        binding.tvFileName.text =
            sourceFile.name.removeSuffix(".9genc").removeSuffix(".9GENC")
        binding.tvDocInfo.text        = "Opening…"
        binding.progressBar.isVisible = true
        binding.webView.isVisible     = false
        binding.bottomBar.isVisible   = false
        binding.tvError.isVisible     = false

        binding.btnShare.setOnClickListener { shareFile(sourceFile) }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { prepareFile(sourceFile) }
            if (_binding == null) return@launch

            result.fold(
                onSuccess  = { dispatchToDocViewer(it) },
                onFailure  = { showError("Could not open: ${it.message}") }
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── File preparation ───────────────────────────────────────────────────────

    private suspend fun prepareFile(source: File): Result<File> {
        val is9genc = source.name.endsWith(".9genc", ignoreCase = true)
        if (!is9genc) return Result.success(source)

        val ctx     = requireContext()
        val tmpDir  = File(ctx.cacheDir, TMP_DIR_NAME).also { it.mkdirs() }
        val tmpName = source.name.removeSuffix(".9genc").removeSuffix(".9GENC")
        val tmpFile = File(tmpDir, tmpName)

        val ok = EncryptionUtils.decryptDeviceToTempFile(
            source              = source,
            tempFile            = tmpFile,
            sessionKeyDecryptor = { DeviceKeyManager.decryptSessionKey(it) }
        )

        return if (ok) Result.success(tmpFile)
        else Result.failure(
            IllegalStateException("Decryption failed — file may be encrypted for a different device.")
        )
    }

    // ── DocViewer dispatch ────────────────────────────────────────────────────

    /**
     * Launches DocViewerActivity with DocSourceType.PATH (absolute file path)
     * and DocEngine.INTERNAL (fully offline rendering via Apache POI).
     *
     * Falls back to a system ACTION_VIEW chooser only if the launch itself throws
     * (e.g. the library AAR is missing from the build).
     */
    private fun dispatchToDocViewer(file: File) {
        try {
            DocViewerActivity.launchDocViewer(
                requireActivity() as AppCompatActivity,  // host Activity
                DocSourceType.PATH,         // read from absolute storage path (offline)
                file.absolutePath           // full path to the .pptx file
            )
            // Pop this fragment immediately so the back stack after DocViewerActivity
            // finishes returns straight to the file browser, not this loading screen.
            findNavController().popBackStack()
        } catch (e: Exception) {
            val msg = "DocViewer launch failed (${e.javaClass.simpleName}: ${e.message}). " +
                      "Falling back to system viewer."
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            fallbackToSystemViewer(file)
        }
    }

    // ── System-viewer fallback ─────────────────────────────────────────────────

    private fun fallbackToSystemViewer(file: File) {
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri,
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            findNavController().popBackStack()
        } catch (_: Exception) {
            showError("No app found to open this PPTX file.")
        }
    }

    // ── Share ──────────────────────────────────────────────────────────────────

    private fun shareFile(file: File) {
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share…"
            ))
        } catch (_: Exception) {
            Snackbar.make(binding.root, "Could not share file.", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Error display ──────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        if (_binding == null) return
        binding.progressBar.isVisible = false
        binding.tvError.text          = msg
        binding.tvError.isVisible     = true
    }
}
