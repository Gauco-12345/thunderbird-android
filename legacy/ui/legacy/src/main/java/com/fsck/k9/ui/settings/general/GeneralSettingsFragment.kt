package com.fsck.k9.ui.settings.general

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.CheckBoxPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.work.WorkInfo
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.feature.launcher.FeatureLauncherTarget
import app.k9mail.feature.telemetry.api.TelemetryManager
import com.fsck.k9.job.K9JobManager
import com.fsck.k9.ui.BuildConfig
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.extensions.withArguments
import com.fsck.k9.ui.dcmail.DcAiSettingsActivity
import com.fsck.k9.ui.dcmail.DcFaviconLoader
import com.fsck.k9.ui.dcmail.DcMessageActions
import com.fsck.k9.ui.dcmail.DcTemplatesActivity
import com.fsck.k9.ui.dcmail.DcUpdateActivity
import com.fsck.k9.ui.observe
import com.fsck.k9.ui.settings.notificationactions.NotificationActionsSettingsActivity
import com.fsck.k9.ui.settings.remove
import com.google.android.material.snackbar.Snackbar
import com.takisoft.preferencex.PreferenceFragmentCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class GeneralSettingsFragment : PreferenceFragmentCompat() {
    private val viewModel: GeneralSettingsViewModel by viewModel()
    private val dataStore: GeneralSettingsDataStore by inject()
    private val telemetryManager: TelemetryManager by inject()
    private val featureFlagProvider: FeatureFlagProvider by inject()
    private val jobManager: K9JobManager by inject()

    private var rootKey: String? = null
    private var currentUiState: GeneralSettingsUiState? = null
    private var snackbar: Snackbar? = null

    // DC Mail: password-protected backup
    private val dcBackupCreateContract =
        registerForActivityResult(CreateDocument("application/octet-stream")) { contentUri ->
            val activity = activity
            if (contentUri != null && activity != null) {
                com.fsck.k9.ui.dcmail.DcBackupUi.createBackup(activity, contentUri)
            }
        }
    private val dcBackupRestoreContract =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            val activity = activity
            if (uri != null && activity != null) {
                com.fsck.k9.ui.dcmail.DcBackupUi.restoreBackup(activity, uri)
            }
        }

    private val exportLogsResultContract = registerForActivityResult(CreateDocument("text/plain")) { contentUri ->
        if (contentUri != null) {
            viewModel.exportLogs(contentUri)
        }
    }

    private val exportSyncDebugLogsResultContract =
        registerForActivityResult(CreateDocument("text/plain")) { contentUri ->
            if (contentUri != null) {
                viewModel.fileExport(contentUri.toString())
            }
        }
    private val choseFileSyncDebugLogsResultContract =
        registerForActivityResult(CreateDocument("text/plain")) { contentUri ->
            if (contentUri != null) {
                context?.contentResolver?.takePersistableUriPermission(
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                jobManager.scheduleDebugLogLimit(contentUri.toString()).observe(
                    this,
                ) { workInfo: WorkInfo? ->
                    if (workInfo != null) {
                        if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                            viewModel.showExportSnackbar(true)
                        } else if (workInfo.state == WorkInfo.State.FAILED) {
                            viewModel.showExportSnackbar(false)
                        }
                    }
                }
            }
        }

    // DC Mail: lets the user hide the "Waiting for new mail" push notification in the system settings
    private fun openPushChannelSettings() {
        val context = context ?: return
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "push")
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }
    }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = dataStore
        this.rootKey = rootKey
        setPreferencesFromResource(R.xml.general_settings, rootKey)

        findPreference<Preference>("notification_actions_settings")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.let { NotificationActionsSettingsActivity.start(it) }
                true
            }
        findPreference<Preference>("dcmail_push_notification")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                openPushChannelSettings()
                true
            }
        findPreference<Preference>("dcmail_update")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.let { DcUpdateActivity.start(it) }
                true
            }
        findPreference<Preference>("dcmail_backup_create")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                dcBackupCreateContract.launch(com.fsck.k9.ui.dcmail.DcBackupUi.suggestedFileName())
                true
            }
        findPreference<Preference>("dcmail_backup_restore")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                dcBackupRestoreContract.launch(arrayOf("*/*"))
                true
            }
        findPreference<Preference>("dcmail_ai_settings")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.let { DcAiSettingsActivity.start(it) }
                true
            }
        findPreference<Preference>("dcmail_templates")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.let { DcTemplatesActivity.start(it) }
                true
            }
        findPreference<CheckBoxPreference>("dcmail_favicons")?.let { preference ->
            preference.isChecked = DcFaviconLoader.isEnabled(preference.context)
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                DcFaviconLoader.setEnabled(pref.context, newValue == true)
                true
            }
        }
        findPreference<MultiSelectListPreference>("dcmail_message_actions")?.let { preference ->
            preference.values = DcMessageActions.load(preference.context)
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val keys = (newValue as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
                DcMessageActions.save(pref.context, keys)
                true
            }
        }
        findPreference<CheckBoxPreference>("dcmail_categories")?.let { preference ->
            preference.isChecked = com.fsck.k9.ui.dcmail.DcMailCategories.isEnabled(preference.context)
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                com.fsck.k9.ui.dcmail.DcMailCategories.setEnabled(pref.context, newValue == true)
                true
            }
        }
        findPreference<CheckBoxPreference>("dcmail_phishing_warning")?.let { preference ->
            preference.isChecked = com.fsck.k9.ui.dcmail.DcMessageInsights.isPhishingWarningEnabled(preference.context)
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                com.fsck.k9.ui.dcmail.DcMessageInsights.setPhishingWarningEnabled(pref.context, newValue == true)
                true
            }
        }
        findPreference<Preference>("dcmail_invoice_forward")?.let { preference ->
            fun updateSummary() {
                val address = com.fsck.k9.ui.dcmail.DcMessageInsights.invoiceForwardAddress(preference.context)
                preference.summary = address.ifEmpty { getString(R.string.dcmail_invoice_setting_summary) }
            }
            updateSummary()
            preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                com.fsck.k9.ui.dcmail.DcBanners.askInvoiceAddress(requireContext()) { updateSummary() }
                true
            }
        }
        findPreference<Preference>("remote_feature_flag")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                FeatureLauncherActivity.launch(
                    context = requireContext(),
                    target = FeatureLauncherTarget.RemoteFeatureFlag,
                )
                true
            }

        val listener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (!(newValue as Boolean)) {
                jobManager.cancelDebugLogLimit()
                exportSyncDebugLogsResultContract.launch(formatFileExportUriString())
            } else {
                choseFileSyncDebugLogsResultContract.launch(formatFileExportUriString())
            }
            true
        }
        findPreference<Preference>("sync_debug_logging")?.onPreferenceChangeListener = listener
        featureFlagProvider.provide(GeneratedFeatureFlagKey.DISABLE_FONT_SIZE_CONFIG)
            .onEnabled {
                val parentPreference = findPreference<PreferenceCategory>("global_preferences")
                val fontSizePreferenceScreen = findPreference<PreferenceScreen>("font_size")

                if (parentPreference != null && fontSizePreferenceScreen != null) {
                    parentPreference.removePreference(fontSizePreferenceScreen)
                }
            }

        findPreference<Preference>("debug_secret_debug_screen")?.apply {
            if (!BuildConfig.DEBUG) {
                remove()
                onPreferenceClickListener = null
            } else {
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    viewModel.onOpenSecretDebugScreen(requireContext())

                    true
                }
            }
        }

        initializeDataCollection()

        viewModel.uiState.observe(this) { uiState ->
            updateUiState(uiState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissSnackbar()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = preferenceScreen.title

        val menuHost = requireActivity()
        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    if (rootKey == PREFERENCE_SCREEN_DEBUGGING) {
                        menuInflater.inflate(R.menu.debug_settings_option, menu)
                    }
                }

                override fun onPrepareMenu(menu: Menu) {
                    if (rootKey == PREFERENCE_SCREEN_DEBUGGING) {
                        currentUiState?.let { uiState ->
                            menu.findItem(R.id.exportLogs).isEnabled = uiState.isExportLogsMenuEnabled
                        }
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.exportLogs -> {
                            exportLogsResultContract.launch(GeneralSettingsViewModel.DEFAULT_FILENAME)
                            true
                        }

                        R.id.exportSyncLogs -> {
                            exportSyncDebugLogsResultContract.launch(formatFileExportUriString())
                            true
                        }

                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
    }

    private fun initializeDataCollection() {
        if (!telemetryManager.isTelemetryFeatureIncluded()) {
            findPreference<Preference>(PREFERENCE_DATA_COLLECTION)?.remove()
        }
    }

    private fun updateUiState(uiState: GeneralSettingsUiState) {
        val oldUiState = currentUiState
        currentUiState = uiState

        if (oldUiState?.isExportLogsMenuEnabled != uiState.isExportLogsMenuEnabled) {
            setExportLogsMenuEnabled()
        }

        if (oldUiState?.snackbarState != uiState.snackbarState) {
            setSnackbarState(uiState.snackbarState)
        }
    }

    private fun setExportLogsMenuEnabled() {
        requireActivity().invalidateOptionsMenu()
    }

    private fun setSnackbarState(snackbarState: SnackbarState) {
        when (snackbarState) {
            SnackbarState.Hidden -> dismissSnackbar()
            SnackbarState.ExportLogSuccess -> showSnackbar(R.string.debug_export_logs_success)
            SnackbarState.ExportLogFailure -> showSnackbar(R.string.debug_export_logs_failure)
        }
    }

    private fun dismissSnackbar() {
        snackbar?.dismiss()
        snackbar = null
    }

    private fun showSnackbar(message: Int) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_INDEFINITE)
            .also { snackbar = it }
            .show()
    }

    private fun formatFileExportUriString(): String {
        val now = Calendar.getInstance()
        return String.format(
            Locale.US,
            "%s_%s.txt",
            DEFAULT_SYNC_FILENAME,
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time),
        )
    }

    companion object {
        private const val PREFERENCE_SCREEN_DEBUGGING = "debug_preferences"
        private const val PREFERENCE_DATA_COLLECTION = "data_collection"
        const val DEFAULT_SYNC_FILENAME = "thunderbird-sync-logs"

        fun create(rootKey: String? = null) = GeneralSettingsFragment().withArguments(ARG_PREFERENCE_ROOT to rootKey)
    }
}
