package com.radiozport.ninegfiles.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.*
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.OperationResult
import com.radiozport.ninegfiles.databinding.ActivityMainBinding
import com.radiozport.ninegfiles.ui.dialogs.BatchRenameDialog
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModelFactory
import com.radiozport.ninegfiles.utils.AppLockManager
import com.radiozport.ninegfiles.utils.AppLockState
import com.radiozport.ninegfiles.utils.FileUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    /** True while biometric auth is pending after the app returns from background.
     *  The source of truth is [AppLockState] (set by the ProcessLifecycleObserver
     *  in NineGFilesApp); this local flag is kept for immediate onResume reads
     *  before the StateFlow collection starts. */
    private var lockPending: Boolean
        get()      = AppLockState.lockPending.value
        set(value) { if (!value) AppLockState.consume() }

    val viewModel: FileExplorerViewModel by viewModels {
        FileExplorerViewModelFactory(application)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) viewModel.refresh()
        else showPermissionRationale()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
            viewModel.refresh()
        else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoreStatusBar()
        setupNavigation()
        setupToolbar()
        setupBottomNav()
        observeViewModel()
        requestStoragePermissions()
        handleIncomingIntent(intent)
        // Restore the last-visited folder on cold start (if preference is on and no explicit intent)
        if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
            lifecycleScope.launch {
                val app = application as com.radiozport.ninegfiles.NineGFilesApp
                val remember = app.preferences.rememberLastPath.first()
                if (remember) {
                    val lastPath = app.preferences.lastPath.first()
                    if (lastPath.isNotEmpty() && java.io.File(lastPath).isDirectory) {
                        viewModel.navigate(lastPath)
                    }
                }
            }
        }
    }

    /**
     * Ensures the status bar background matches the AppBarLayout surface exactly.
     *
     * Problem: targeting API 35 forces edge-to-edge mode, making
     * window.statusBarColor a no-op.  DrawerLayout (fitsSystemWindows=true)
     * then draws its own coloured rectangle behind the status bar — defaulting
     * to colorPrimary — which does NOT match the AppBarLayout's colorSurface.
     *
     * Fix: resolve colorSurface from the current theme (black in night, white
     * in day) and pass it to DrawerLayout.setStatusBarBackgroundColor() so the
     * region it paints matches the AppBar.  The window.statusBarColor call is
     * kept as a harmless pre-API-35 fallback.
     */
    private fun restoreStatusBar() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Resolve colorSurface from the active theme (night/day qualifier handled
        // automatically by the resource system).
        @androidx.annotation.ColorInt
        val surfaceColor = resources.getColor(R.color.md_theme_surface, theme)

        // On API 35+ the DrawerLayout background is what actually shows behind
        // the status bar — align it with the AppBarLayout surface colour.
        binding.drawerLayout.setStatusBarBackgroundColor(surfaceColor)

        // Pre-API-35 fallback (ignored on API 35+ forced edge-to-edge).
        window.statusBarColor = surfaceColor

        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = !isNight   // dark icons in day, light icons in night
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.explorerFragment, R.id.searchFragment,
                  R.id.toolsFragment, R.id.bookmarksFragment),
            binding.drawerLayout
        )
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.isInSelectionMode.value) viewModel.clearSelection()
            else if (!navController.navigateUp(appBarConfiguration)) onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBottomNav() {
        // setupWithNavController keeps the visual tab indicator in sync with the
        // current destination and enables multi-back-stack save/restore for
        // non-home tabs. We then override the item-selected listener below to
        // fix the Home button behaviour.
        binding.bottomNav.setupWithNavController(navController)
        binding.navigationView.setupWithNavController(navController)

        // Populate the nav-drawer version label from PackageManager so build.gradle
        // is the single source of truth — no hardcoded version strings elsewhere.
        try {
            val versionName = packageManager
                .getPackageInfo(packageName, 0).versionName
            binding.navigationView.getHeaderView(0)
                .findViewById<android.widget.TextView>(R.id.tvNavVersion)
                ?.text = "v$versionName"
        } catch (_: Exception) { }

        // Override item-selected so the Home tab ALWAYS pops the entire back
        // stack back to homeFragment, no matter how deep navigation went.
        // Without this override, fragments opened via a raw navigate() call
        // (e.g. quick-access Downloads button) bypass the NavigationUI
        // save/restore mechanism, causing the default handler to silently
        // re-navigate to explorerFragment instead of going back to Home.
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.homeFragment) {
                // Pop everything above homeFragment (the start destination).
                // Returns false only if homeFragment is not in the stack, which
                // should never happen — the navigate() fallback is a safety net.
                val popped = navController.popBackStack(R.id.homeFragment, false)
                if (!popped) {
                    navController.navigate(R.id.homeFragment)
                }
                true
            } else {
                // All other tabs use the standard NavigationUI logic which
                // correctly handles multi-back-stack save/restore.
                NavigationUI.onNavDestinationSelected(item, navController)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val showBottom = destination.id in listOf(
                R.id.homeFragment, R.id.explorerFragment, R.id.searchFragment,
                R.id.toolsFragment, R.id.bookmarksFragment)
            binding.bottomNav.visibility = if (showBottom) android.view.View.VISIBLE else android.view.View.GONE
            // Only reserve space for the bottom nav when it is actually visible.
            // Without this, full-screen viewers (ePub, PDF, media, …) inherit an
            // 80 dp dead zone at the bottom even though the nav bar is hidden.
            val bottomPad = if (showBottom) (80 * resources.displayMetrics.density).toInt() else 0
            binding.navHostFragment.setPadding(0, 0, 0, bottomPad)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.viewMode.collectLatest { invalidateOptionsMenu() }
                }
                launch {
                    viewModel.isInSelectionMode.collectLatest { inSelectionMode ->
                        invalidateOptionsMenu()
                        if (inSelectionMode)
                            binding.toolbar.title = "${viewModel.selectionCount.value} selected"
                    }
                }
                launch {
                    viewModel.selectionCount.collectLatest { count ->
                        if (viewModel.isInSelectionMode.value)
                            binding.toolbar.title = "$count selected"
                    }
                }
                launch {
                    viewModel.operationResult.collectLatest { result ->
                        when (result) {
                            is OperationResult.Success ->
                                Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT)
                                    .setAnchorView(binding.bottomNav).show()
                            is OperationResult.Failure ->
                                Snackbar.make(binding.root, "Error: ${result.error}", Snackbar.LENGTH_LONG)
                                    .setAnchorView(binding.bottomNav)
                                    .setAction("Dismiss") {}.show()
                            else -> {}
                        }
                    }
                }
                // Apply / clear FLAG_KEEP_SCREEN_ON whenever the preference changes.
                launch {
                    (application as com.radiozport.ninegfiles.NineGFilesApp)
                        .preferences.keepScreenOn.collectLatest { keep ->
                        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else      window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (viewModel.isInSelectionMode.value) menuInflater.inflate(R.menu.menu_selection, menu)
        else {
            menuInflater.inflate(R.menu.menu_main, menu)
            // Update the toggle icon to reflect the current view mode
            val toggleItem = menu.findItem(R.id.action_view_toggle)
            toggleItem?.setIcon(when (viewModel.viewMode.value) {
                com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> R.drawable.ic_view_list
                com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> R.drawable.ic_view_list
                com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> R.drawable.ic_view_list
            })
            toggleItem?.title = when (viewModel.viewMode.value) {
                com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> "Grid View"
                com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> "Compact View"
                com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> "List View"
            }
            menu.findItem(R.id.action_show_hidden)?.isChecked = viewModel.showHidden.value
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val selected = viewModel.getSelectedFileItems()
        return when (item.itemId) {
            R.id.action_select_all    -> { viewModel.selectAll(); true }
            R.id.action_copy          -> { viewModel.copy(selected); true }
            R.id.action_cut           -> { viewModel.cut(selected); true }
            R.id.action_delete        -> { confirmTrash(selected.size); true }
            R.id.action_delete_permanently -> { confirmPermanentDelete(selected.size); true }
            R.id.action_shred         -> { confirmShred(selected.size); true }
            R.id.action_batch_rename  -> {
                if (selected.isNotEmpty()) {
                    BatchRenameDialog(selected) { template ->
                        viewModel.batchRename(selected, template)
                    }.show(supportFragmentManager, "BatchRenameDialog")
                }
                true
            }
            R.id.action_compress      -> {
                if (selected.isNotEmpty()) {
                    com.radiozport.ninegfiles.ui.dialogs.CompressDialog(selected) { name ->
                        viewModel.compress(selected, name)
                    }.show(supportFragmentManager, "CompressDialog")
                }
                true
            }
            R.id.action_compress_encrypted -> {
                if (selected.isNotEmpty()) {
                    com.radiozport.ninegfiles.ui.dialogs.EncryptedZipDialog.show(
                        supportFragmentManager, selected
                    ) { path ->
                        com.google.android.material.snackbar.Snackbar
                            .make(binding.root, "Saved: $path", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.bottomNav).show()
                        viewModel.refresh()
                    }
                }
                true
            }
            R.id.action_share         -> {
                if (selected.size == 1) {
                    val item = selected.first()
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", item.file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = item.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share"))
                }
                true
            }
            R.id.action_view_toggle -> {
                val next = when (viewModel.viewMode.value) {
                    com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> com.radiozport.ninegfiles.data.model.ViewMode.GRID
                    com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> com.radiozport.ninegfiles.data.model.ViewMode.COMPACT
                    com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> com.radiozport.ninegfiles.data.model.ViewMode.LIST
                }
                viewModel.setViewMode(next)
                true
            }
            R.id.action_sort -> {
                com.radiozport.ninegfiles.ui.dialogs.SortDialog(
                    viewModel.getEffectiveSortOption()
                ) { sortOption ->
                    if (navController.currentDestination?.id == R.id.searchFragment) {
                        viewModel.setSortOption(sortOption)
                    } else {
                        viewModel.setFolderSortOption(sortOption)
                    }
                }.show(supportFragmentManager, "SortDialog")
                true
            }
            R.id.action_show_hidden -> {
                val newVal = !viewModel.showHidden.value
                viewModel.setShowHidden(newVal)
                item.isChecked = newVal
                true
            }
            R.id.action_settings -> { navController.navigate(R.id.settingsFragment); true }
            R.id.action_search   -> { navController.navigate(R.id.searchFragment); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmTrash(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Move $count item(s) to Trash?")
            .setPositiveButton("Move to Trash") { _, _ -> viewModel.trash(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmPermanentDelete(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permanently delete $count item(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.delete(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmShred(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Securely shred $count item(s)?")
            .setMessage("Files will be overwritten 3 times then deleted. Cannot be undone.")
            .setPositiveButton("Shred") { _, _ -> viewModel.shred(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onSupportNavigateUp() = navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    // ─── App Lock gate ────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        // NineGFilesApp's ProcessLifecycleObserver is the primary mechanism for
        // setting the lock-pending flag across all entry points. MainActivity's
        // onStop is a belt-and-suspenders backup for the case where the observer
        // hasn't fired yet (e.g. the activity is the only one in the stack and
        // the process is about to die).
        if (AppLockManager.isAppLockEnabled(this)) AppLockState.markPendingIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        if (lockPending && AppLockManager.isAppLockEnabled(this)) {
            lockPending = false          // clears AppLockState via the property setter
            binding.root.alpha = 0f
            AppLockManager.authenticate(
                activity = this,
                title    = "Unlock 9G Files",
                subtitle = "Authenticate to continue"
            ) { success, _ ->
                if (success) {
                    binding.root.alpha = 1f
                } else {
                    AppLockState.markPendingIfEnabled(this)   // re-arm for next resume
                    finish()
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("9G Files needs access to manage all files on your device.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        manageStorageLauncher.launch(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setCancelable(false).show()
            }
        } else {
            storagePermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Denied")
            .setMessage("Without storage permission the app cannot function. Please grant it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    // ─── Intent routing ───────────────────────────────────────────────────────

    /**
     * Called for the launch intent (onCreate) AND for new intents delivered while
     * the activity is already running at the top of the stack (onNewIntent).
     */
    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            // Home-screen shortcut → open a specific folder in the explorer
            "com.radiozport.ninegfiles.ACTION_OPEN_PATH" -> {
                val path = intent.getStringExtra("open_path") ?: return
                openPathInExplorer(path)
            }
            // System file-open request (e.g. Downloads notification, "Open with…" picker)
            Intent.ACTION_VIEW, Intent.ACTION_OPEN_DOCUMENT -> {
                val uri = intent.data ?: return
                openViewIntent(uri, intent.type)
            }
            // Wi-Fi Direct / notification deep-link
            else -> {
                intent?.getStringExtra("navigate_to")?.let { path -> openPathInExplorer(path) }
            }
        }
    }

    /**
     * Receives new intents when the activity is already running with
     * launchMode="singleTop".  Without this override the intent is swallowed
     * and the file is never opened.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)   // keep getIntent() in sync for any later reads
        handleIncomingIntent(intent)
    }

    /**
     * Resolves the incoming [uri] to a local [File] (copying content:// streams
     * to the app cache when necessary) then navigates to the appropriate viewer.
     */
    private fun openViewIntent(uri: Uri, mimeHint: String?) {
        lifecycleScope.launch {
            val file: File? = withContext(Dispatchers.IO) { resolveUriToFile(uri) }
            if (file == null || !file.exists()) return@launch
            dispatchToViewer(file)
        }
    }

    /**
     * Resolves a URI to a [File] the in-app viewers can access.
     *
     * * `file://` URIs → use the path directly.
     * * `content://` URIs → copy to `[cacheDir]/intent_open/<displayName>`.
     *
     * Returns `null` if resolution fails.
     */
    private fun resolveUriToFile(uri: Uri): File? = try {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }
            "content" -> {
                // Recover the original display name so the extension is preserved.
                val displayName = contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else null
                } ?: uri.lastPathSegment ?: "unknown_file"

                val cacheFile = File(cacheDir, "intent_open/${displayName}")
                    .also { it.parentFile?.mkdirs() }

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
                cacheFile.takeIf { it.exists() }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    /**
     * Navigates to the right in-app viewer for [file], waiting for [homeFragment]
     * to be the current destination if the nav graph hasn't settled yet.
     */
    private fun dispatchToViewer(file: File) {
        if (navController.currentDestination?.id == R.id.homeFragment) {
            navigateToViewer(file)
        } else {
            // The nav graph is still initialising — wait for the start destination.
            navController.addOnDestinationChangedListener(
                object : NavController.OnDestinationChangedListener {
                    override fun onDestinationChanged(
                        controller: NavController,
                        destination: androidx.navigation.NavDestination,
                        arguments: Bundle?
                    ) {
                        if (destination.id == R.id.homeFragment) {
                            controller.removeOnDestinationChangedListener(this)
                            navigateToViewer(file)
                        }
                    }
                }
            )
        }
    }

    /**
     * Maps [file]'s extension to the correct in-app viewer and navigates to it.
     * Falls back to a system `ACTION_VIEW` intent for unrecognised types.
     */
    private fun navigateToViewer(file: File) {
        val ext  = file.extension.lowercase()
        val path = file.absolutePath
        val args = Bundle()

        when (ext) {
            // ── Images ───────────────────────────────────────────────────────
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg" -> {
                args.putString("path", path)
                navController.navigate(R.id.imageViewerFragment, args)
            }
            // ── PDF ──────────────────────────────────────────────────────────
            "pdf" -> {
                args.putString("pdfPath", path)
                navController.navigate(R.id.pdfViewerFragment, args)
            }
            // ── eBook ────────────────────────────────────────────────────────
            "epub" -> {
                args.putString("epubPath", path)
                navController.navigate(R.id.epubReaderFragment, args)
            }
            // ── Word / OpenDocument text ──────────────────────────────────────
            "docx", "doc", "odt" -> {
                args.putString("docxPath", path)
                navController.navigate(R.id.docxViewerFragment, args)
            }
            // ── RTF ──────────────────────────────────────────────────────────
            "rtf" -> {
                args.putString("rtfPath", path)
                navController.navigate(R.id.rtfViewerFragment, args)
            }
            // ── Markdown ─────────────────────────────────────────────────────
            "md", "markdown" -> {
                args.putString("mdPath", path)
                navController.navigate(R.id.markdownViewerFragment, args)
            }
            // ── Spreadsheets ──────────────────────────────────────────────────
            "xlsx", "xlsm", "ods", "csv" -> {
                args.putString("spreadsheetPath", path)
                navController.navigate(R.id.spreadsheetViewerFragment, args)
            }
            // ── Presentations ─────────────────────────────────────────────────
            "pptx" -> {
                args.putString("pptxPath", path)
                navController.navigate(R.id.presentationViewerFragment, args)
            }
            // ── Archives ─────────────────────────────────────────────────────
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "tgz", "tbz2", "txz" -> {
                args.putString("archivePath", path)
                navController.navigate(R.id.zipBrowserFragment, args)
            }
            // ── APK ──────────────────────────────────────────────────────────
            "apk" -> {
                args.putString("apkPath", path)
                navController.navigate(R.id.apkInfoFragment, args)
            }
            // ── Audio / Video ─────────────────────────────────────────────────
            "mp3", "flac", "ogg", "wav", "aac", "opus", "wma", "m4a",
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "wmv", "ts" -> {
                args.putString("mediaPath", path)
                navController.navigate(R.id.mediaInfoFragment, args)
            }
            // ── Text / code / everything else ─────────────────────────────────
            else -> {
                if (FileUtils.isTextFile(file)) {
                    args.putString("filePath", path)
                    navController.navigate(R.id.textEditorFragment, args)
                } else {
                    // Nothing in-app can show this type — hand off to the system.
                    val item = FileItem.fromFile(file)
                    try {
                        val uri = FileProvider.getUriForFile(
                            this, "$packageName.fileprovider", file)
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: ActivityNotFoundException) { /* no handler — silently ignore */ }
                }
            }
        }
    }

    private fun openPathInExplorer(path: String) {
        viewModel.navigate(path)
        navController.addOnDestinationChangedListener(object :
            NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: androidx.navigation.NavDestination,
                arguments: Bundle?
            ) {
                if (destination.id == R.id.homeFragment) {
                    controller.navigate(R.id.explorerFragment)
                    controller.removeOnDestinationChangedListener(this)
                }
            }
        })
    }
}
