package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** Owns the single persisted update state shared by automatic and manual checks. */
@Suppress("ApplySharedPref", "UseKtx")
class UpdateRepository(
    private val context: Context,
    private val releaseClient: ReleaseSource,
    private val downloadStore: UpdateDownloadStore? = runCatching {
        context.getSystemService(DownloadManager::class.java)
    }.getOrNull()?.let { AndroidUpdateDownloadStore(context, it) },
    private val installPreparer: UpdateInstallPreparer = UpdateInstaller(context),
    private val installSessionStore: UpdateInstallSessionStore = AndroidUpdateInstallSessionStore(context),
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val foregroundChecker: () -> Boolean = {
        (context.applicationContext as? XtraApp)?.isInForeground == true
    },
    private val pendingInstallStarter: (Intent) -> Unit = { intent ->
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    },
) {

    private val preferences = context.tokenPrefs()
    private val settingsPreferences = context.prefs()
    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val ready = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /**
     * A one-shot automatic prompt. Checks while MainActivity is not STARTED may lose this event
     * intentionally; the persisted Available state and Settings indicator remain authoritative,
     * and returning to the app must not replay a modal caused by a background check.
     */
    private val _automaticPromptEvents = MutableSharedFlow<UpdateRelease>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val resetGeneration = AtomicLong(0L)
    private var checkJob: Job? = null
    private val checkLock = Mutex()
    private var downloadMonitorJob: Job? = null
    private var monitoredDownloadId: Long? = null
    // Lock order is check -> install -> download. Check claims install ownership for its state
    // transition and each result publication; download and install callbacks must take their
    // corresponding lock before inspecting or publishing ownership-backed state.
    private val downloadLock = Mutex()
    private val installLock = Mutex()
    private var activeDownloadId: Long? = preferences.getLong(C.UPDATE_DOWNLOAD_ID, -1L).takeIf { it >= 0L }
    private var activeInstallSessionId: Int? = preferences.getInt(C.UPDATE_INSTALL_SESSION_ID, -1).takeIf { it >= 0 }
    private var activeInstallReleaseId: String? = preferences.getString(C.UPDATE_INSTALL_RELEASE_ID, null)
    private var installCommitStarted: Boolean = preferences.getBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, false)
    private val installGate = UpdateInstallGate(activeInstallSessionId)
    private var pendingInstallIntent: Intent? = preferences.getString(C.UPDATE_INSTALL_PENDING_INTENT, null)?.let { encoded ->
        runCatching { Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME) }.getOrNull()
    }
    private var pendingInstallLaunchAttempted = false
    private var lastCheckNetworkLibrary: String? = null
    private var lastCheckUrl: String = C.DEFAULT_UPDATE_URL

    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val automaticPromptEvents: SharedFlow<UpdateRelease> = _automaticPromptEvents.asSharedFlow()

    init {
        scope.launch {
            try {
                recoverPersistedState()
            } finally {
                ready.complete(Unit)
            }
        }
    }

    fun check(networkLibrary: String?, url: String = C.DEFAULT_UPDATE_URL, automatic: Boolean = false) {
        val generation = resetGeneration.get()
        if (checkJob?.isActive == true || _state.value is UpdateState.Checking || _state.value.isLongRunningUpdateOperation()) return
        lastCheckNetworkLibrary = networkLibrary
        lastCheckUrl = url
        checkJob = scope.launch {
            checkLock.withLock {
                try {
                    ready.await()
                    ensureCurrentCheck(generation)
                    val checkStart = installLock.withLock {
                        if (activeInstallSessionId != null ||
                            _state.value is UpdateState.Checking ||
                            _state.value.isLongRunningUpdateOperation()
                        ) {
                            null
                        } else {
                            CheckStart(_state.value, activeInstallSessionId).also {
                                _state.value = UpdateState.Checking
                            }
                        }
                    } ?: return@launch
                    val previousState = checkStart.previousState
                    val preservedAction = previousState.preservedAction()
                    markAttempted()
                    var stage = UpdateStage.CHECK
                    try {
                        val response = releaseClient.fetch(url, networkLibrary)
                        ensureCurrentCheck(generation)
                        stage = UpdateStage.PARSE
                        val parsed = ReleaseParser.parse(response, url)
                        ensureCurrentCheck(generation)
                        val release = (parsed as? ReleaseParseResult.Success)?.release
                            ?: throw UpdateException((parsed as ReleaseParseResult.Failure).error, stage = UpdateStage.PARSE)
                        val now = System.currentTimeMillis()
                        val deferred = isDeferred(release)
                        when (UpdatePolicy.decide(
                            installedVersionName = BuildConfig.VERSION_NAME,
                            installedBuildNumber = installedBuildNumber,
                            release = release,
                            ignoredReleaseId = ignoredReleaseId,
                            automatic = automatic,
                            deferred = deferred,
                        )) {
                            UpdateDecision.Available -> {
                                stage = UpdateStage.ASSET_SELECTION
                                val asset = UpdatePolicy.selectAsset(release).getOrElse { throw it }
                                publishCheckResult(generation, checkStart.installSessionId) {
                                    // Serialize candidate replacement with UI-triggered downloads.
                                    // Whichever operation gets this lock first owns the old
                                    // candidate; the other operation revalidates persisted state.
                                    replacePersistedRelease(release, asset)
                                    restoreDownloadOrShow(release)
                                    val finalState = when (val current = _state.value) {
                                        is UpdateState.Available -> current.copy(
                                            previouslySkipped = isSkipped(release),
                                            previouslyDeferred = !automatic && deferred,
                                        )
                                        is UpdateState.Skipped -> if (!automatic && isSkipped(release)) {
                                            UpdateState.Available(release, previouslySkipped = true, previouslyDeferred = false)
                                        } else {
                                            current
                                        }
                                        is UpdateState.Deferred -> if (!automatic && deferred) {
                                            UpdateState.Available(release, previouslyDeferred = true)
                                        } else {
                                            current
                                        }
                                        else -> current
                                    }
                                    _state.value = finalState
                                    if (automatic && finalState is UpdateState.Available &&
                                        finalState.release.id == release.id
                                    ) {
                                        ensureCurrentCheck(generation)
                                        _automaticPromptEvents.tryEmit(release)
                                    }
                                }
                            }
                            UpdateDecision.Current -> {
                                publishCheckResult(generation, checkStart.installSessionId) {
                                    clearReleaseAndDownload()
                                    _state.value = UpdateState.UpToDate(release, now)
                                }
                            }
                            UpdateDecision.Ignored -> {
                                publishCheckResult(generation, checkStart.installSessionId) {
                                    discardStalePersistedRelease(release.id)
                                    _state.value = UpdateState.Skipped(release)
                                }
                            }
                            UpdateDecision.Deferred -> {
                                publishCheckResult(generation, checkStart.installSessionId) {
                                    discardStalePersistedRelease(release.id)
                                    _state.value = UpdateState.Deferred(release)
                                }
                            }
                        }
                        ensureCurrentCheck(generation)
                        markSuccessful(now)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        if (automatic && previousState is UpdateState.Deferred && isDeferred(previousState.release)) {
                            publishCheckResult(generation, checkStart.installSessionId) {
                                _state.value = previousState
                            }
                            return@launch
                        }
                        val errorStage = (error as? UpdateException)?.stage ?: stage
                        val cause = UpdateErrorMapper.fromThrowable(error)
                        publishCheckResult(generation, checkStart.installSessionId) {
                            _state.value = UpdateState.Error(
                                stage = errorStage,
                                cause = cause,
                                retryable = UpdatePolicy.isRetryable(errorStage, cause),
                                release = preservedAction?.release,
                                artifact = preservedAction?.artifact,
                                preservedAction = preservedAction?.action,
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                }
            }
        }
    }

    private suspend fun publishCheckResult(
        generation: Long,
        expectedInstallSessionId: Int?,
        result: suspend () -> Unit,
    ) {
        installLock.withLock {
            downloadLock.withLock {
                ensureCheckCanPublish(generation, expectedInstallSessionId)
                result()
            }
        }
    }

    fun checkIfDue(networkLibrary: String?, url: String = C.DEFAULT_UPDATE_URL) {
        if (!settingsPreferences.getBoolean(C.UPDATE_CHECK_ENABLED, true)) return
        val lastChecked = preferences.getLong(C.UPDATE_LAST_CHECKED, 0L)
        if (lastChecked > 0L && System.currentTimeMillis() - lastChecked < DAY_MILLIS) return
        check(networkLibrary, url, automatic = true)
    }

    fun skip(release: UpdateRelease) {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                val authoritative = authoritativeDownloadRelease(release) ?: return@withLock
                preferences.edit { putString(C.UPDATE_IGNORED_VERSION, authoritative.id) }
                _state.value = UpdateState.Skipped(authoritative)
            }
        }
    }

    fun defer(release: UpdateRelease) {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                val authoritative = authoritativeDownloadRelease(release) ?: return@withLock
                preferences.edit {
                    putString(C.UPDATE_NOT_NOW_VERSION, authoritative.id)
                    putLong(C.UPDATE_NOT_NOW_UNTIL, System.currentTimeMillis() + NOT_NOW_MILLIS)
                }
                _state.value = UpdateState.Deferred(authoritative)
            }
        }
    }

    fun isDeferred(release: UpdateRelease): Boolean {
        return preferences.getString(C.UPDATE_NOT_NOW_VERSION, null) == release.id &&
            preferences.getLong(C.UPDATE_NOT_NOW_UNTIL, 0L) > System.currentTimeMillis()
    }

    fun undoSkip() {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                preferences.edit {
                    remove(C.UPDATE_IGNORED_VERSION)
                    remove(C.UPDATE_NOT_NOW_VERSION)
                    remove(C.UPDATE_NOT_NOW_UNTIL)
                }
                val release = loadPersistedRelease()
                    ?: (_state.value as? UpdateState.Skipped)?.release
                    ?: (_state.value as? UpdateState.Available)?.release
                    ?: return@withLock
                if (UpdatePolicy.isNewer(BuildConfig.VERSION_NAME, installedBuildNumber, release)) {
                    _state.value = UpdateState.Available(release)
                } else {
                    _state.value = UpdateState.UpToDate(release, lastSuccessfulCheck)
                }
            }
        }
    }

    fun reset() {
        resetGeneration.incrementAndGet()
        scope.launch {
            ready.await()
            checkJob?.cancelAndJoin()
            // Hold checkLock while clearing state so a check cannot start between cancellation
            // and the reset mutation. Lock order is check -> install -> download.
            checkLock.withLock {
                installLock.withLock {
                    downloadLock.withLock {
                        // Reset must invalidate the system session before its ownership
                        // keys disappear, otherwise a callback can arrive with no owner.
                        abandonActiveInstallSession()
                        clearReleaseAndDownload()
                        preferences.edit {
                            remove(C.UPDATE_IGNORED_VERSION)
                            remove(C.UPDATE_LAST_CHECKED)
                            remove(C.UPDATE_LAST_ATTEMPTED)
                            remove(C.UPDATE_NOT_NOW_VERSION)
                            remove(C.UPDATE_NOT_NOW_UNTIL)
                        }
                        _state.value = UpdateState.Idle
                    }
                }
            }
        }
    }

    fun download(release: UpdateRelease) {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                val authoritative = authoritativeDownloadRelease(release) ?: return@withLock
                downloadLocked(authoritative)
            }
        }
    }

    fun downloadCurrent() {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                val authoritative = authoritativeDownloadRelease() ?: return@withLock
                downloadLocked(authoritative)
            }
        }
    }

    private suspend fun downloadLocked(release: UpdateRelease) {
        if (_state.value is UpdateState.Checking ||
            _state.value is UpdateState.Downloading ||
            _state.value is UpdateState.Downloaded ||
            _state.value is UpdateState.Installing ||
            _state.value is UpdateState.AwaitingUserAction
        ) return
        try {
            val asset = UpdatePolicy.selectAsset(release).getOrElse { throw it }
            if (activeDownloadId != null) {
                if (loadPersistedRelease()?.id == release.id) {
                    clearSuppressionForExplicitDownload(release)
                    reconcileDownload(release)
                    return
                }
                downloadStore?.remove(activeDownloadId!!)
                clearDownloadReference()
            }
            clearSuppressionForExplicitDownload(release)
            val fileName = fileNameFor(release, asset)
            val id = downloadStore?.enqueue(release, asset, fileName)
                ?: throw UpdateException(UpdateError.DownloadFailed)
            activeDownloadId = id
            if (!preferences.edit()
                    .putLong(C.UPDATE_DOWNLOAD_ID, id)
                    .putString(C.UPDATE_DOWNLOAD_FILE, fileName)
                    .commit()
            ) {
                removeDownload(id)
                clearDownloadReference()
                throw UpdateException(UpdateError.DownloadFailed)
            }
            _state.value = UpdateState.Downloading(release, DownloadProgress(0L, asset.size))
            monitorDownload(release)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val cause = UpdateErrorMapper.fromDownloadThrowable(error)
            val stage = (error as? UpdateException)?.stage ?: UpdateStage.DOWNLOAD
            _state.value = UpdateState.Error(
                stage,
                cause,
                UpdatePolicy.isRetryable(stage, cause),
                release = release.takeIf { stage == UpdateStage.DOWNLOAD },
            )
        }
    }

    fun cancelDownload() {
        scope.launch {
            ready.await()
            downloadLock.withLock {
                val cancelledDownloadId = activeDownloadId
                clearDownloadReference()
                removeDownload(cancelledDownloadId)
                _state.value = loadPersistedRelease()?.let(::stateForUnownedRelease) ?: UpdateState.Idle
            }
        }
    }

    fun install() {
        scope.launch {
            ready.await()
            installLock.withLock {
                downloadLock.withLock {
                    val current = _state.value
                    val downloaded = when (current) {
                        is UpdateState.Downloaded -> current
                        is UpdateState.Error -> current.takeIf {
                            it.release != null && it.artifact != null &&
                                (it.primaryAction() == UpdatePrimaryAction.INSTALL ||
                                    (it.cause == UpdateError.InstallPermissionDenied && canInstallPackages()))
                        }?.let { UpdateState.Downloaded(it.release!!, it.artifact!!) }
                        else -> null
                    } ?: return@withLock
                    if (activeInstallSessionId != null) {
                        if (pendingInstallIntent != null && foregroundChecker()) {
                            resumePendingInstallInternal()
                        }
                        return@withLock
                    }
                    if (!installGate.tryBegin()) return@withLock
                    _state.value = UpdateState.Installing(downloaded.release, downloaded.artifact, null)
                    var prepared: PreparedUpdateInstall? = null
                    var commitStarted = false
                    try {
                        val preparedInstall = installPreparer.prepare(downloaded.release, downloaded.artifact)
                        prepared = preparedInstall
                        val sessionId = preparedInstall.sessionId
                        activeInstallSessionId = sessionId
                        activeInstallReleaseId = downloaded.release.id
                        if (!persistInstallOwnership(sessionId, downloaded.release.id)) {
                            throw UpdateException(UpdateError.InstallFailed)
                        }
                        installGate.markCommitted(sessionId)
                        _state.value = UpdateState.Installing(downloaded.release, downloaded.artifact, sessionId)
                        if (!persistInstallCommitStarted()) {
                            throw UpdateException(UpdateError.InstallFailed)
                        }
                        commitStarted = true
                        preparedInstall.commit()
                    } catch (cancellation: CancellationException) {
                        prepared?.abandon()
                        if (activeInstallSessionId != null) {
                            abandonActiveInstallSession()
                        } else {
                            installGate.abort()
                            clearInstallReference()
                        }
                        throw cancellation
                    } catch (error: Throwable) {
                        prepared?.abandon()
                        if (commitStarted) {
                            // commit() is asynchronous, but a synchronous commit failure leaves the
                            // downloaded artifact usable for another attempt.
                            abandonActiveInstallSession()
                            _state.value = UpdateState.Downloaded(downloaded.release, downloaded.artifact)
                        } else {
                            if (activeInstallSessionId != null) {
                                abandonActiveInstallSession()
                            } else {
                                installGate.abort()
                                clearInstallReference()
                            }
                            val cause = UpdateErrorMapper.fromInstallThrowable(error)
                            if (cause == UpdateError.DownloadedFileMissing || cause == UpdateError.DownloadFailed) {
                                removeDownload(activeDownloadId)
                                clearDownloadReference()
                                _state.value = UpdateState.Error(
                                    UpdateStage.DOWNLOAD,
                                    cause,
                                    retryable = UpdatePolicy.isRetryable(UpdateStage.DOWNLOAD, cause),
                                    release = downloaded.release,
                                )
                            } else {
                                _state.value = UpdateState.Error(
                                    UpdateStage.INSTALL,
                                    cause,
                                    UpdatePolicy.isRetryable(UpdateStage.INSTALL, cause),
                                    release = downloaded.release,
                                    artifact = downloaded.artifact,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun retry() {
        scope.launch {
            ready.await()
            val current = _state.value as? UpdateState.Error ?: return@launch
            when (current.retryAction()) {
                UpdateRetryAction.CHECK -> {
                    checkJob?.join()
                    check(lastCheckNetworkLibrary, lastCheckUrl)
                }
                UpdateRetryAction.DOWNLOAD -> current.release?.let(::download)
                UpdateRetryAction.INSTALL -> install()
                null -> Unit
            }
        }
    }

    suspend fun handleDownloadComplete(id: Long) {
        ready.await()
        downloadLock.withLock {
            if (id != activeDownloadId) return@withLock
            try {
                reconcileDownload(loadPersistedRelease())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (id != activeDownloadId) return@withLock
                _state.value = UpdateState.Error(
                    UpdateStage.DOWNLOAD,
                    UpdateErrorMapper.fromDownloadThrowable(error),
                    retryable = true,
                    release = loadPersistedRelease(),
                )
            }
        }
    }

    suspend fun handleInstallResult(
        status: Int,
        callbackReleaseId: String?,
        callbackSessionId: Int?,
        pendingUserAction: Intent? = null,
    ) {
        ready.await()
        installLock.withLock {
            downloadLock.withLock {
                handleInstallResultInternal(status, callbackReleaseId, callbackSessionId, pendingUserAction)
            }
        }
    }

    private suspend fun handleInstallResultInternal(
        status: Int,
        callbackReleaseId: String?,
        callbackSessionId: Int?,
        pendingUserAction: Intent?,
    ) {
        if (!UpdatePolicy.installCallbackMatches(activeInstallReleaseId, activeInstallSessionId, callbackReleaseId, callbackSessionId)) {
            return
        }
        val release = loadPersistedRelease()
        val artifact = currentArtifact()
        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                clearInstallReference()
                clearReleaseAndDownload()
                _state.value = UpdateState.Idle
            }
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (release == null) {
                    abandonActiveInstallSession()
                    _state.value = UpdateState.Error(UpdateStage.INSTALL, UpdateError.DownloadedFileMissing, false)
                } else if (pendingUserAction == null) {
                    abandonActiveInstallSession()
                    _state.value = UpdateState.Error(
                        UpdateStage.INSTALL,
                        UpdateError.InstallFailed,
                        retryable = artifact != null,
                        release = release,
                        artifact = artifact,
                    )
                } else {
                    val sessionId = activeInstallSessionId ?: return
                    pendingInstallIntent = pendingUserAction
                    if (!persistPendingInstallIntent()) {
                        abandonActiveInstallSession()
                        _state.value = UpdateState.Error(
                            UpdateStage.INSTALL,
                            UpdateError.InstallFailed,
                            retryable = artifact != null,
                            release = release,
                            artifact = artifact,
                        )
                    } else {
                        _state.value = UpdateState.AwaitingUserAction(release, artifact, sessionId)
                        if (foregroundChecker()) {
                            resumePendingInstallInternal()
                        } else {
                            postInstallNotification(release)
                        }
                    }
                }
            }
            else -> {
                val error = when (status) {
                    android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateError.InstallCancelled
                    android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateError.InstallPermissionDenied
                    android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                    android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> UpdateError.IncompatibleApk
                    else -> UpdateError.InstallFailed
                }
                clearInstallReference()
                _state.value = if (release != null && artifact != null) {
                    UpdateState.Error(UpdateStage.INSTALL, error, UpdatePolicy.isRetryable(UpdateStage.INSTALL, error), release, artifact)
                } else {
                    UpdateState.Error(UpdateStage.INSTALL, error, UpdatePolicy.isRetryable(UpdateStage.INSTALL, error), release)
                }
            }
        }
    }

    fun refreshInstallPermission() {
        scope.launch {
            ready.await()
            installLock.withLock {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
                    _state.value = UpdatePolicy.restoreAfterInstallPermission(_state.value, true)
                }
            }
        }
    }

    fun launchPendingInstall() {
        scope.launch {
            ready.await()
            installLock.withLock {
                launchPendingInstallInternal(explicit = true)
            }
        }
    }

    /** Resumes a pending installer action from lifecycle recovery, at most once per process. */
    fun resumePendingInstall() {
        scope.launch {
            ready.await()
            installLock.withLock {
                resumePendingInstallInternal()
            }
        }
    }

    private fun resumePendingInstallInternal() {
        launchPendingInstallInternal(explicit = false)
    }

    private fun launchPendingInstallInternal(explicit: Boolean) {
        if (!foregroundChecker() || !pendingInstallIsCurrent()) return
        val pending = pendingInstallIntent ?: return
        if (!explicit && pendingInstallLaunchAttempted) return
        // Explicit Continue is allowed to retry the authoritative pending action, but every
        // successful launch still counts as attempted so lifecycle callbacks cannot loop.
        pendingInstallLaunchAttempted = true
        startPendingInstall(pending)
    }

    val lastSuccessfulCheck: Long
        get() = preferences.getLong(C.UPDATE_LAST_CHECKED, 0L)

    val lastAttemptedCheck: Long
        get() = preferences.getLong(C.UPDATE_LAST_ATTEMPTED, 0L)

    val ignoredReleaseId: String?
        get() = preferences.getString(C.UPDATE_IGNORED_VERSION, null)

    private val installedBuildNumber: Long?
        get() = UpdateVersionDisplay.installedBuildNumber(
            BuildConfig.VERSION_CODE.toLong(),
            BuildConfig.CI_VERSION_CODE_BASE.toLong(),
        )

    private data class PreservedAction(
        val release: UpdateRelease,
        val artifact: DownloadedArtifact?,
        val action: UpdatePrimaryAction,
    )

    private data class CheckStart(
        val previousState: UpdateState,
        val installSessionId: Int?,
    )

    private fun authoritativeDownloadRelease(requested: UpdateRelease? = null): UpdateRelease? {
        val persisted = loadPersistedRelease() ?: return null
        val current = _state.value.downloadAuthorizationRelease() ?: return null
        if (current.id != persisted.id || requested?.id?.let { it != persisted.id } == true) return null
        return persisted
    }

    // Retryable download errors retain release authorization without exposing a second Download
    // button beside Retry in Settings.
    private fun UpdateState.downloadAuthorizationRelease(): UpdateRelease? = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Deferred -> release
        is UpdateState.Error -> release?.takeIf {
            primaryAction() == UpdatePrimaryAction.DOWNLOAD || retryAction() == UpdateRetryAction.DOWNLOAD
        }
        else -> null
    }

    private fun UpdateState?.preservedAction(): PreservedAction? = when (this) {
        is UpdateState.Available -> PreservedAction(release, null, UpdatePrimaryAction.DOWNLOAD)
        is UpdateState.Deferred -> PreservedAction(release, null, UpdatePrimaryAction.DOWNLOAD)
        is UpdateState.Downloaded -> PreservedAction(release, artifact, UpdatePrimaryAction.INSTALL)
        is UpdateState.Error -> primaryAction()?.let { action ->
            release?.let { PreservedAction(it, artifact, action) }
        }
        else -> null
    }

    private fun isSkipped(release: UpdateRelease): Boolean =
        ignoredReleaseId == release.id || ignoredReleaseId == release.displayVersion

    private fun stateForUnownedRelease(release: UpdateRelease): UpdateState = when {
        isSkipped(release) -> UpdateState.Skipped(release)
        isDeferred(release) -> UpdateState.Deferred(release)
        else -> UpdateState.Available(release)
    }

    private fun discardStalePersistedRelease(remoteReleaseId: String) {
        val persistedReleaseId = preferences.getString(C.UPDATE_AVAILABLE_VERSION, null)
        if (UpdatePolicy.shouldDiscardPersistedRelease(persistedReleaseId, remoteReleaseId)) {
            clearReleaseAndDownload()
        }
    }

    private suspend fun recoverPersistedState() {
        installLock.withLock {
            val release = loadPersistedRelease()
            if (activeInstallSessionId != null) {
                val sessionId = activeInstallSessionId!!
                val snapshot = runCatching { installSessionStore.inspect(sessionId) }.getOrNull()
                val recoveryAction = UpdatePolicy.installRecoveryAction(
                    snapshot = snapshot,
                    pendingIntentPersisted = pendingInstallIntent != null,
                    commitStarted = installCommitStarted,
                )
                if (release == null || activeInstallReleaseId != release.id || recoveryAction == UpdateInstallRecoveryAction.RETRY) {
                    abandonActiveInstallSession()
                } else if (recoveryAction == UpdateInstallRecoveryAction.RECOMMIT) {
                    val recommitted = runCatching {
                        installSessionStore.recommit(sessionId, release.id)
                    }.getOrDefault(false)
                    if (!recommitted) {
                        // Old platforms cannot tell whether commit() ran before session commit. If
                        // the session cannot be safely re-committed, abandon it once and recover the
                        // still-owned download instead of waiting forever for a callback that may not exist.
                        abandonActiveInstallSession()
                    }
                }
            } else if (pendingInstallIntent != null) {
                pendingInstallIntent = null
                persistPendingInstallIntent()
            }
            downloadLock.withLock {
                val currentRelease = loadPersistedRelease() ?: return@withLock
                reconcileDownload(currentRelease)
            }
        }
    }

    private suspend fun reconcileDownload(release: UpdateRelease?) {
        if (release == null) {
            _state.value = UpdateState.Idle
            return
        }
        if (!UpdatePolicy.isNewer(BuildConfig.VERSION_NAME, installedBuildNumber, release)) {
            clearReleaseAndDownload()
            _state.value = UpdateState.UpToDate(release, lastSuccessfulCheck)
            return
        }
        if (activeInstallSessionId != null && activeInstallReleaseId == release.id) {
            restoreInstallState(release)
            return
        }
        val id = activeDownloadId
        if (id == null) {
            _state.value = if (activeInstallSessionId != null && activeInstallReleaseId == release.id) {
                installState(release)
            } else {
                stateForUnownedRelease(release)
            }
            return
        }
        val store = downloadStore ?: run {
            clearDownloadReference()
            _state.value = UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadFailed, true, release)
            return
        }
        val record = try {
            store.query(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _state.value = UpdateState.Error(
                UpdateStage.DOWNLOAD,
                UpdateErrorMapper.fromDownloadThrowable(error),
                retryable = true,
                release = release,
            )
            return
        }
        if (record == null) {
            clearDownloadReference()
            _state.value = if (activeInstallSessionId != null && activeInstallReleaseId == release.id) {
                installState(release)
            } else {
                stateForUnownedRelease(release)
            }
            return
        }
        val status = record.status
        val downloaded = record.downloadedBytes
        val total = record.totalBytes
        when (status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                _state.value = UpdateState.Downloading(release, DownloadProgress(downloaded, total))
                monitorDownload(release)
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (!record.fileAvailable || downloaded <= 0L) {
                    clearDownloadReference()
                    _state.value = UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadedFileMissing, true, release)
                } else {
                    val fileName = preferences.getString(C.UPDATE_DOWNLOAD_FILE, "xtra-update.apk") ?: "xtra-update.apk"
                    _state.value = UpdateState.Downloaded(release, DownloadedArtifact(id, record.uri, fileName, downloaded))
                }
            }
            else -> {
                clearDownloadReference()
                _state.value = UpdateState.Error(UpdateStage.DOWNLOAD, UpdateError.DownloadFailed, true, release)
            }
        }
    }

    private suspend fun restoreDownloadOrShow(release: UpdateRelease) {
        reconcileDownload(release)
    }

    private fun monitorDownload(release: UpdateRelease) {
        val monitoredId = activeDownloadId ?: return
        if (downloadMonitorJob?.isActive == true && monitoredDownloadId == monitoredId) return
        downloadMonitorJob?.cancel()
        monitoredDownloadId = monitoredId
        downloadMonitorJob = scope.launch {
            try {
                while (true) {
                    val shouldContinue = downloadLock.withLock {
                        if (activeDownloadId != monitoredId) {
                            false
                        } else {
                            reconcileDownload(release)
                            activeDownloadId == monitoredId && _state.value is UpdateState.Downloading
                        }
                    }
                    if (!shouldContinue) break
                    delay(DOWNLOAD_POLL_MILLIS)
                }
            } finally {
                if (monitoredDownloadId == monitoredId) {
                    monitoredDownloadId = null
                    downloadMonitorJob = null
                }
            }
        }
    }

    private fun replacePersistedRelease(release: UpdateRelease, asset: UpdateAsset) {
        val oldId = preferences.getString(C.UPDATE_AVAILABLE_VERSION, null)
        val oldDownloadId = activeDownloadId
        val editor = preferences.edit()
            .putString(C.UPDATE_AVAILABLE_VERSION, release.id)
            .putString(C.UPDATE_AVAILABLE_TITLE, release.title)
            .putString(C.UPDATE_AVAILABLE_BODY, release.rawBody)
            .putString(C.UPDATE_AVAILABLE_URL, release.releaseUrl)
            .putString(C.UPDATE_AVAILABLE_DOWNLOAD_URL, asset.downloadUrl)
            .putString(C.UPDATE_AVAILABLE_ASSET_NAME, asset.name)
            .putLong(C.UPDATE_AVAILABLE_SIZE, asset.size ?: -1L)
        release.expectedVersionCode?.let { editor.putLong(C.UPDATE_AVAILABLE_EXPECTED_VERSION_CODE, it) }
            ?: editor.remove(C.UPDATE_AVAILABLE_EXPECTED_VERSION_CODE)
        release.publishedAt?.let { editor.putString(C.UPDATE_AVAILABLE_PUBLISHED_AT, it) }
            ?: editor.remove(C.UPDATE_AVAILABLE_PUBLISHED_AT)
        if (oldId != release.id) {
            // Commit the new metadata and unlink the old DownloadManager row as one
            // preference transition. A process restart can then only recover the
            // candidate, never install an old artifact under its metadata.
            editor.remove(C.UPDATE_DOWNLOAD_ID)
            editor.remove(C.UPDATE_DOWNLOAD_FILE)
            editor.remove(C.UPDATE_DOWNLOADED_VERSION)
        }
        if (!editor.commit()) {
            throw UpdateException(UpdateError.DownloadFailed, stage = UpdateStage.DOWNLOAD)
        }
        if (oldId != release.id) {
            activeDownloadId = null
            cancelDownloadMonitor()
            oldDownloadId?.let { oldDownload ->
                runCatching { downloadStore?.remove(oldDownload) }
                    .onFailure { android.util.Log.w(TAG, "Could not remove stale update download", it) }
            }
        }
    }

    private fun loadPersistedRelease(): UpdateRelease? {
        val tag = preferences.getString(C.UPDATE_AVAILABLE_VERSION, null) ?: return null
        val assetUrl = preferences.getString(C.UPDATE_AVAILABLE_DOWNLOAD_URL, null) ?: return null
        val assetName = preferences.getString(C.UPDATE_AVAILABLE_ASSET_NAME, "app-release.apk") ?: "app-release.apk"
        val response = buildJsonObject {
            put("tag_name", tag)
            put("name", preferences.getString(C.UPDATE_AVAILABLE_TITLE, tag) ?: tag)
            put("body", preferences.getString(C.UPDATE_AVAILABLE_BODY, "") ?: "")
            put("html_url", preferences.getString(C.UPDATE_AVAILABLE_URL, C.DEFAULT_UPDATE_URL) ?: C.DEFAULT_UPDATE_URL)
            preferences.getString(C.UPDATE_AVAILABLE_PUBLISHED_AT, null)?.let { put("published_at", it) }
            preferences.getLong(C.UPDATE_AVAILABLE_EXPECTED_VERSION_CODE, -1L)
                .takeIf { it > 0L }
                ?.let { expectedVersionCode ->
                    put(RELEASE_METADATA_RESPONSE_KEY, buildJsonObject {
                        put("versionCode", expectedVersionCode)
                    })
                }
            put("draft", false)
            put("prerelease", false)
            put("assets", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("name", assetName)
                    put("browser_download_url", assetUrl)
                    put("content_type", APK_MIME_TYPE)
                    put("size", preferences.getLong(C.UPDATE_AVAILABLE_SIZE, -1L))
                })
            })
        }
        return (ReleaseParser.parse(response, C.DEFAULT_UPDATE_URL) as? ReleaseParseResult.Success)?.release
    }

    private fun currentArtifact(): DownloadedArtifact? {
        val id = activeDownloadId ?: return null
        // DownloadManager state can disappear while a PackageInstaller callback is
        // being delivered. Treat that as a missing artifact rather than letting a
        // receiver coroutine die before it can publish a terminal/retryable state.
        val record = runCatching { downloadStore?.query(id) }.getOrNull() ?: return null
        val uri = record.uri ?: return null
        val size = record.downloadedBytes.takeIf { it > 0L } ?: return null
        val name = preferences.getString(C.UPDATE_DOWNLOAD_FILE, "xtra-update.apk") ?: "xtra-update.apk"
        return DownloadedArtifact(id, uri, name, size)
    }

    private fun clearInstallReference() {
        cancelInstallNotification()
        installGate.finish(activeInstallSessionId)
        activeInstallSessionId = null
        activeInstallReleaseId = null
        installCommitStarted = false
        pendingInstallIntent = null
        pendingInstallLaunchAttempted = false
        preferences.edit()
            .remove(C.UPDATE_INSTALL_SESSION_ID)
            .remove(C.UPDATE_INSTALL_RELEASE_ID)
            .remove(C.UPDATE_INSTALL_COMMIT_STARTED)
            .remove(C.UPDATE_INSTALL_PENDING_INTENT)
            .commit()
    }

    private fun cancelInstallNotification() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(INSTALL_NOTIFICATION_ID)
        }
    }

    private fun persistInstallOwnership(sessionId: Int, releaseId: String): Boolean =
        preferences.edit()
            .putInt(C.UPDATE_INSTALL_SESSION_ID, sessionId)
            .putString(C.UPDATE_INSTALL_RELEASE_ID, releaseId)
            .putBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, false)
            .commit()
            .also { if (it) installCommitStarted = false }

    private fun persistInstallCommitStarted(): Boolean = preferences.edit()
        .putBoolean(C.UPDATE_INSTALL_COMMIT_STARTED, true)
        .commit()
        .also { if (it) installCommitStarted = true }

    private fun ensureCurrentCheck(generation: Long) {
        if (generation != resetGeneration.get()) {
            throw CancellationException("Updater reset superseded this check")
        }
    }

    private fun clearSuppressionForExplicitDownload(release: UpdateRelease) {
        val ignored = ignoredReleaseId
        val notNow = preferences.getString(C.UPDATE_NOT_NOW_VERSION, null)
        val ignoredMatches = ignored == release.id || ignored == release.displayVersion
        val notNowMatches = notNow == release.id || notNow == release.displayVersion
        if (!ignoredMatches && !notNowMatches) return
        val editor = preferences.edit()
        if (ignoredMatches) editor.remove(C.UPDATE_IGNORED_VERSION)
        if (notNowMatches) {
            editor.remove(C.UPDATE_NOT_NOW_VERSION)
            editor.remove(C.UPDATE_NOT_NOW_UNTIL)
        }
        editor.commit()
    }

    private fun ensureCheckCanPublish(generation: Long, expectedInstallSessionId: Int?) {
        ensureCurrentCheck(generation)
        if (activeInstallSessionId != expectedInstallSessionId ||
            activeInstallSessionId != null ||
            _state.value is UpdateState.Installing ||
            _state.value is UpdateState.AwaitingUserAction
        ) {
            throw CancellationException("Install superseded this check")
        }
    }

    private fun abandonActiveInstallSession() {
        activeInstallSessionId?.let { sessionId ->
            runCatching { installSessionStore.abandon(sessionId) }
        }
        clearInstallReference()
    }

    private fun persistPendingInstallIntent(): Boolean {
        val encoded = pendingInstallIntent?.let { intent ->
            runCatching { intent.toUri(Intent.URI_INTENT_SCHEME) }.getOrNull()
        }
        val editor = preferences.edit()
        encoded?.let { editor.putString(C.UPDATE_INSTALL_PENDING_INTENT, it) }
            ?: editor.remove(C.UPDATE_INSTALL_PENDING_INTENT)
        return editor.commit()
    }

    private fun installState(release: UpdateRelease): UpdateState = pendingInstallIntent?.let {
        UpdateState.AwaitingUserAction(release, currentArtifact(), activeInstallSessionId!!)
    } ?: UpdateState.Installing(release, currentArtifact(), activeInstallSessionId)

    private fun restoreInstallState(release: UpdateRelease) {
        _state.value = installState(release)
        if (_state.value is UpdateState.AwaitingUserAction) {
            if (foregroundChecker()) resumePendingInstallInternal() else postInstallNotification(release)
        }
    }

    private fun UpdateState?.isLongRunningUpdateOperation(): Boolean = this is UpdateState.Downloading ||
        this is UpdateState.Installing ||
        this is UpdateState.AwaitingUserAction

    private fun pendingInstallIsCurrent(): Boolean {
        val sessionId = activeInstallSessionId ?: return false
        val release = loadPersistedRelease() ?: return false
        val state = _state.value as? UpdateState.AwaitingUserAction ?: return false
        return activeInstallReleaseId == release.id &&
            state.release.id == release.id &&
            state.sessionId == sessionId
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun startPendingInstall(intent: Intent) {
        try {
            // Launching the authoritative action from the app should retire the notification
            // just as tapping its content intent would; terminal cleanup remains centralized in
            // clearInstallReference().
            cancelInstallNotification()
            pendingInstallStarter(intent)
        } catch (error: Throwable) {
            val release = loadPersistedRelease()
            val artifact = currentArtifact()
            abandonActiveInstallSession()
            _state.value = if (release != null && artifact != null) {
                UpdateState.Downloaded(release, artifact)
            } else {
                UpdateState.Error(UpdateStage.INSTALL, UpdateError.DownloadedFileMissing, false, release, artifact)
            }
            android.util.Log.e(TAG, "Could not start the package installer", error)
        }
    }

    private fun postInstallNotification(release: UpdateRelease) {
        val intent = pendingInstallIntent ?: return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channelId = context.getString(R.string.notification_updates_channel_id)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(channelId) == null
        ) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(R.string.notification_updates_channel_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            activeInstallSessionId ?: C.UPDATE_INSTALL_REQUEST_CODE,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            INSTALL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(context.getString(R.string.update_install_notification_title))
                .setContentText(context.getString(R.string.update_ready_to_install, release.displayVersion))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun clearReleaseAndDownload() {
        removeDownload(activeDownloadId)
        clearDownloadReference()
        preferences.edit()
            .remove(C.UPDATE_AVAILABLE_VERSION)
            .remove(C.UPDATE_AVAILABLE_TITLE)
            .remove(C.UPDATE_AVAILABLE_BODY)
            .remove(C.UPDATE_AVAILABLE_URL)
            .remove(C.UPDATE_AVAILABLE_PUBLISHED_AT)
            .remove(C.UPDATE_AVAILABLE_DOWNLOAD_URL)
            .remove(C.UPDATE_AVAILABLE_ASSET_NAME)
            .remove(C.UPDATE_AVAILABLE_SIZE)
            .remove(C.UPDATE_AVAILABLE_EXPECTED_VERSION_CODE)
            .remove(C.UPDATE_DOWNLOADED_VERSION)
            .commit()
        clearInstallReference()
    }

    private fun removeDownload(id: Long?) {
        id ?: return
        runCatching { downloadStore?.remove(id) }
            .onFailure { android.util.Log.w(TAG, "Could not remove update download $id", it) }
    }

    private fun clearDownloadReference() {
        cancelDownloadMonitor()
        activeDownloadId = null
        preferences.edit()
            .remove(C.UPDATE_DOWNLOAD_ID)
            .remove(C.UPDATE_DOWNLOAD_FILE)
            .commit()
    }

    private fun cancelDownloadMonitor() {
        monitoredDownloadId = null
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
    }

    private fun fileNameFor(release: UpdateRelease, asset: UpdateAsset): String {
        val suffix = release.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "xtra-update-$suffix-${asset.name.substringAfterLast('/')}"
    }

    private fun markAttempted() {
        preferences.edit { putLong(C.UPDATE_LAST_ATTEMPTED, System.currentTimeMillis()) }
    }

    private fun markSuccessful(now: Long) {
        preferences.edit { putLong(C.UPDATE_LAST_CHECKED, now) }
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val INSTALL_NOTIFICATION_ID = 4202
        private const val TAG = "UpdateRepository"
        private const val DAY_MILLIS = 86_400_000L
        private const val NOT_NOW_MILLIS = DAY_MILLIS
        private const val DOWNLOAD_POLL_MILLIS = 500L
    }
}
