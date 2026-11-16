package com.mackseoul.vcardnative.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.data.profile.SavedProfile
import com.mackseoul.vcardnative.data.profile.StaleRevisionException
import com.mackseoul.vcardnative.data.profile.StoreException
import com.mackseoul.vcardnative.data.sharing.QuickShareSelectionStore
import com.mackseoul.vcardnative.data.sharing.QuickShareQrGenerator
import com.mackseoul.vcardnative.data.sharing.QuickShareQrResult
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.profile.withSocialProfiles
import com.mackseoul.vcardnative.domain.profile.moveSocialProfile
import com.mackseoul.vcardnative.domain.profile.legacySocialFields
import com.mackseoul.vcardnative.domain.profile.updateLegacySocialValue
import com.mackseoul.vcardnative.domain.profile.updateLegacySocialVisibility
import com.mackseoul.vcardnative.domain.profile.toDraft
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import com.mackseoul.vcardnative.domain.profile.DraftImage
import com.mackseoul.vcardnative.domain.profile.ImageAsset
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.ContactQrException
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.domain.sharing.QuickShareContactExport
import com.mackseoul.vcardnative.domain.sharing.QuickSharePreview
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import com.mackseoul.vcardnative.data.profile.RevisionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.keys.PublicKeyStatus
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import com.mackseoul.vcardnative.domain.backup.BackupException
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.backup.RestoreCandidate
import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.data.backup.StagedBackup
import com.mackseoul.vcardnative.platform.BackupImageValidation
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetMenuDestination

enum class ProfileDestination {
    Home, MyCard, Profile, QrPresentation, OnlineCardQr,
    QuickShareSelectInformation, QuickShareReview, QuickShareQr,
    Editor, Appearance, Backup,
    Welcome, SetupDetails, SetupContact, SetupReview, Visibility, Security, HostedProfile, ProfileImages,
}

enum class ProfileEditorTab { Details, Contact, Advanced }

enum class SocialPlatformOption(val canonicalName: String?, val displayName: String) {
    LinkedIn("linkedin", "LinkedIn"),
    GitHub("github", "GitHub"),
    Facebook("facebook", "Facebook"),
    Instagram("instagram", "Instagram"),
    XTwitter("x", "X (Twitter)"),
    YouTube("youtube", "YouTube"),
    TikTok("tiktok", "TikTok"),
    Threads("threads", "Threads"),
    Bluesky("bluesky", "Bluesky"),
    Mastodon("mastodon", "Mastodon"),
    Other(null, "Other"),
}

data class SocialPlatformSelection(val option: SocialPlatformOption, val customName: String = "") {
    val platformName: String get() = option.canonicalName ?: customName
    val automaticLabel: String get() = if (option == SocialPlatformOption.Other) customName else option.displayName
    fun select(next: SocialPlatformOption): SocialPlatformSelection = copy(option = next)

    fun applyTo(
        entry: com.mackseoul.vcardnative.domain.profile.SocialProfile,
        previous: SocialPlatformSelection,
    ): com.mackseoul.vcardnative.domain.profile.SocialProfile = entry.copy(
        platform = platformName,
        label = if (entry.label.isBlank() || entry.label == previous.automaticLabel) automaticLabel else entry.label,
    )

    companion object {
        fun fromSaved(platform: String): SocialPlatformSelection {
            val option = SocialPlatformOption.entries.firstOrNull {
                it.canonicalName != null && (it.canonicalName.equals(platform, ignoreCase = true) ||
                    it.displayName.equals(platform, ignoreCase = true))
            } ?: if (platform.equals("twitter", ignoreCase = true)) SocialPlatformOption.XTwitter else null
            return if (option != null) SocialPlatformSelection(option)
            else SocialPlatformSelection(SocialPlatformOption.Other, platform)
        }
    }
}

fun ProfileField.editorTab(): ProfileEditorTab = when (this) {
    ProfileField.FullName, ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName,
    ProfileField.ProfessionalTitle, ProfileField.Organization,
    ProfileField.Biography, ProfileField.Location -> ProfileEditorTab.Details
    ProfileField.Phone, ProfileField.Email, ProfileField.Website,
    ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional -> ProfileEditorTab.Contact
    ProfileField.HostedProfileUrl, ProfileField.PublicKey, ProfileField.ProfileImage,
    ProfileField.CoverImage -> ProfileEditorTab.Advanced
}

enum class BackupOperation { Preparing, ReadyForDestination, ChoosingDestination, Writing, ChoosingRestore, Validating, Committing }
data class RestoreReview(
    val stage: StagedBackup,
    val candidate: RestoreCandidate,
    val expected: RevisionToken?,
    val replacingProfileName: String?,
    val keyStatus: PublicKeyStatus?,
    val recoveryToken: String? = null,
)

data class ContactReview(
    val token: RevisionToken,
    val export: ContactExport,
    val contractVersion: Int = ContactExport.CONTRACT_VERSION,
)

data class QuickShareContactHandoff(val id: Long, val export: ContactExport)

data class QuickShareSessionState(
    val savedProfile: SavedProfile,
    val fullName: String?,
    val availableFields: List<ProfileField>,
    val selectedFields: Set<ProfileField>,
    val qrResult: QuickShareQrResult? = null,
    val error: String? = null,
    val selectionPersistenceError: String? = null,
    val pendingContactHandoff: QuickShareContactHandoff? = null,
) {
    val selectedValues: Map<ProfileField, String>
        get() = QuickShareSelection.selectedValues(savedProfile.data.profile.values, selectedFields)

    // Each session update creates a new instance, so this is evaluated once per snapshot/selection change.
    val preview: QuickSharePreview by lazy(LazyThreadSafetyMode.PUBLICATION) {
        QuickSharePreview.from(savedProfile.data.profile, selectedFields)
    }
}

data class ProfileScreenState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saved: SavedProfile? = null,
    val unavailable: String? = null,
    val recoveryToken: String? = null,
    val pendingOperation: String? = null,
    val destination: ProfileDestination = ProfileDestination.Home,
    val draft: ProfileDraft? = null,
    val initialDraft: ProfileDraft? = null,
    val errors: Map<ProfileField, String> = emptyMap(),
    val saveError: String? = null,
    val reviewNormalization: Boolean = false,
    val confirmDiscard: Boolean = false,
    val confirmDeleteProfile: Boolean = false,
    val deletingProfile: Boolean = false,
    val deleteError: String? = null,
    val firstRun: Boolean = false,
    val validationAttempt: Int = 0,
    val appearanceDraft: Appearance? = null,
    val editorCaller: ProfileDestination = ProfileDestination.Profile,
    val editorTab: ProfileEditorTab = ProfileEditorTab.Details,
    val contactExportSettingsFirst: Boolean = false,
    val pendingImageLink: Boolean = false,
    val processingImage: Boolean = false,
    val pickingImage: ImageRole? = null,
    val imageError: String? = null,
    val savedImages: Map<ImageRole, ImageBitmap> = emptyMap(),
    val draftImages: Map<ImageRole, ImageBitmap> = emptyMap(),
    val contactExport: ContactExport? = null,
    val contactReview: ContactReview? = null,
    val contactError: String? = null,
    val contactCleanupError: String? = null,
    val exporting: Boolean = false,
    val handoffNotice: Boolean = false,
    val contactQr: ContactQr? = null,
    val qrFailure: QrFailure? = null,
    val qrLoading: Boolean = false,
    val keyStatus: PublicKeyStatus? = null,
    val draftKeyStatus: PublicKeyStatus? = null,
    val keyError: String? = null,
    val keyNotice: String? = null,
    val pickingKey: Boolean = false,
    val processingKey: Boolean = false,
    val keySaveToken: RevisionToken? = null,
    val handingOffKey: Boolean = false,
    val backupOperation: BackupOperation? = null,
    val backupStage: StagedBackup? = null,
    val backupFileName: String? = null,
    val backupReview: RestoreReview? = null,
    val backupError: String? = null,
    val backupNotice: String? = null,
    val backupCaller: ProfileDestination = ProfileDestination.Profile,
    val pendingBackupLink: Boolean = false,
    val quickShareSession: QuickShareSessionState? = null,
    val quickShareError: String? = null,
    val shortcutQrOrigin: Boolean = false,
    val shareQrOrigin: Boolean = false,
    val pendingShortcutQr: Boolean = false,
    val pendingWidgetLaunch: Boolean = false,
    val pendingWidgetDestination: QuickCardWidgetMenuDestination = QuickCardWidgetMenuDestination.Share,
    val socialPlatformSelections: Map<java.util.UUID, SocialPlatformSelection> = emptyMap(),
) {
    val shouldRefreshContact: Boolean
        get() = !loading && saved != null && unavailable == null &&
            destination in setOf(ProfileDestination.Home, ProfileDestination.QrPresentation)

    val onlineCardUrl: String?
        get() = saved?.data?.value(ProfileField.HostedProfileUrl)?.takeIf {
            !loading && unavailable == null && ProfileValidator.browserUrlCandidate(it, true) != null
        }

    val fullNamePreview: String
        get() = prepareFullNameDraft()?.let { prepared ->
            if (prepared.generate) prepared.generatedName
            else prepared.draft.values[ProfileField.FullName].orEmpty()
        }.orEmpty()

    val dirty: Boolean get() = when (destination) {
        ProfileDestination.Appearance -> appearanceDraft != saved?.data?.appearance
        else -> draft != initialDraft
    }
    val busy: Boolean get() = loading || saving || deletingProfile || processingImage || pickingImage != null || exporting ||
        pickingKey || processingKey || keySaveToken != null || handingOffKey || backupOperation != null
}

fun ProfileScreenState.openOnlineCardQr(): ProfileScreenState =
    if (destination == ProfileDestination.MyCard && !busy && onlineCardUrl != null) {
        copy(destination = ProfileDestination.OnlineCardQr)
    } else this

fun ProfileScreenState.resolveWidgetLaunch(): ProfileScreenState {
    if (!pendingWidgetLaunch || loading || busy || backupReview != null || contactReview != null) return this
    if (unavailable == null && pendingOperation == null &&
        dirty && (draft != null || destination == ProfileDestination.Appearance)) {
        return copy(confirmDiscard = true)
    }
    return copy(
        destination = when {
            unavailable != null || pendingOperation != null -> ProfileDestination.Profile
            saved == null -> ProfileDestination.Welcome
            else -> pendingWidgetDestination.destination
        },
        pendingWidgetLaunch = false, pendingShortcutQr = false, shortcutQrOrigin = false, shareQrOrigin = false,
        pendingWidgetDestination = QuickCardWidgetMenuDestination.Share,
        confirmDiscard = false, quickShareSession = null, quickShareError = null,
    )
}

private data class PreparedFullNameDraft(
    val draft: ProfileDraft,
    val generatedName: String,
    val generate: Boolean,
)

private fun ProfileScreenState.prepareFullNameDraft(): PreparedFullNameDraft? {
    val currentDraft = draft ?: return null
    val baseline = initialDraft ?: ProfileDraft.empty()
    val generate = firstRun || structuredNameFields.any { field ->
        compareNameComponent(currentDraft.values[field].orEmpty()) !=
            compareNameComponent(baseline.values[field].orEmpty())
    }
    val generatedName = structuredNameFields.asSequence()
        .map { field -> normalizeGeneratedNameComponent(currentDraft.values[field].orEmpty()) }
        .filter(String::isNotEmpty)
        .joinToString(" ")
    val fullName = if (generate) generatedName else baseline.values[ProfileField.FullName]
        ?: currentDraft.values[ProfileField.FullName].orEmpty()
    return PreparedFullNameDraft(
        draft = currentDraft.copy(values = currentDraft.values + (ProfileField.FullName to fullName)),
        generatedName = generatedName,
        generate = generate,
    )
}

private fun compareNameComponent(value: String): String = Normalizer.normalize(
    value.trim { it.isWhitespace() || Character.isSpaceChar(it) },
    Normalizer.Form.NFC,
)

private fun normalizeGeneratedNameComponent(value: String): String = buildString {
    var pendingSpace = false
    Normalizer.normalize(value, Normalizer.Form.NFC).forEach { character ->
        if (character.isWhitespace() || Character.isSpaceChar(character)) {
            if (isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) append(' ')
            append(character)
            pendingSpace = false
        }
    }
}

class ProfileViewModel(
    createRepository: () -> ProfileRepository,
    private val readSelectedImage: (Uri, ImageRole) -> DraftImage,
    private val cleanupContactFiles: () -> Unit,
    private val readSelectedPublicKey: (Uri) -> ByteArray = {
        throw PublicKeyException("Public-key document access is unavailable.")
    },
    private val backupStaging: BackupStaging? = null,
    private val readSelectedBackup: (Uri) -> StagedBackup = {
        throw BackupException("Backup document access is unavailable.")
    },
    private val writeSelectedBackup: (Uri, StagedBackup) -> Unit = { _, _ ->
        throw BackupException("Backup document output is unavailable.")
    },
    private val quickShareSelectionStore: QuickShareSelectionStore? = null,
    private val generateQuickShareQr: (Profile, Set<ProfileField>) -> QuickShareQrResult = { profile, selection ->
        QuickShareQrGenerator.generate(profile, selection)
    },
) : ViewModel() {
    private val repository by lazy(createRepository)
    private val mutableState = MutableStateFlow(ProfileScreenState())
    val state = mutableState.asStateFlow()
    private var qrRequest = 0
    private var quickShareRequest = 0
    private var nextQuickShareHandoffId = 0L
    private var backupCleanupAttempted = false

    init { reload() }

    fun reload() {
        if (mutableState.value.busy && !mutableState.value.loading) return
        qrRequest++
        quickShareRequest++
        mutableState.value = mutableState.value.copy(
            loading = true, contactReview = null, contactExport = null, contactQr = null, qrFailure = null, qrLoading = false,
            quickShareSession = null, quickShareError = null,
        )
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    repository.load()
                } catch (error: StoreException) {
                    ProfileRead.Unavailable(requireNotNull(error.message))
                } catch (_: IOException) {
                    ProfileRead.Unavailable("Local storage could not be opened. Saved data has not been reset.")
                }
            }
            val cleanupError = withContext(Dispatchers.IO) {
                if (read is ProfileRead.Unavailable && read.pendingOperation != null) return@withContext null
                try {
                    repository.withFileOperation { cleanupContactFiles() }
                    null
                } catch (error: ContactExportException) {
                    error.message
                } catch (error: StoreException) {
                    error.message
                }
            }
            val backupCleanupError = withContext(Dispatchers.IO) {
                if (backupCleanupAttempted || read is ProfileRead.Unavailable && read.pendingOperation != null) null else {
                    backupCleanupAttempted = true
                    try { repository.withFileOperation { backupStaging?.cleanupAbandoned() }; null }
                    catch (error: BackupException) { error.message }
                    catch (error: StoreException) { error.message }
                }
            }
            when (read) {
                ProfileRead.Empty -> {
                    val draft = ProfileDraft.empty()
                    mutableState.value = ProfileScreenState(
                        loading = false, destination = ProfileDestination.Welcome,
                        draft = draft, initialDraft = draft, firstRun = true,
                        backupError = backupCleanupError,
                        pendingWidgetLaunch = mutableState.value.pendingWidgetLaunch,
                        pendingWidgetDestination = mutableState.value.pendingWidgetDestination,
                    ).resolveWidgetLaunch()
                }
                is ProfileRead.Ready -> {
                    try {
                        val images = withContext(Dispatchers.IO) { loadImages(read.saved) }
                        val keyStatus = withContext(Dispatchers.IO) { loadKeyStatus(read.saved) }
                        mutableState.value = ProfileScreenState(
                            loading = false, saved = read.saved, savedImages = images, keyStatus = keyStatus,
                            contactCleanupError = cleanupError,
                            backupError = backupCleanupError,
                            shortcutQrOrigin = mutableState.value.shortcutQrOrigin,
                            shareQrOrigin = mutableState.value.shareQrOrigin,
                            pendingShortcutQr = mutableState.value.pendingShortcutQr,
                            pendingWidgetLaunch = mutableState.value.pendingWidgetLaunch,
                            pendingWidgetDestination = mutableState.value.pendingWidgetDestination,
                            destination = mutableState.value.destination.takeUnless {
                                it == ProfileDestination.Editor || it == ProfileDestination.Appearance ||
                                    it == ProfileDestination.Welcome || it == ProfileDestination.SetupDetails ||
                                    it == ProfileDestination.SetupContact || it == ProfileDestination.SetupReview ||
                                    it == ProfileDestination.QuickShareSelectInformation ||
                                    it == ProfileDestination.QuickShareReview || it == ProfileDestination.QuickShareQr
                            } ?: ProfileDestination.Home,
                        ).resolveWidgetLaunch()
                        if (mutableState.value.shouldRefreshContact) {
                            refreshContact()
                        }
                    } catch (error: StoreException) {
                        mutableState.value = ProfileScreenState(
                            loading = false, unavailable = error.message,
                            destination = ProfileDestination.Profile,
                            pendingWidgetLaunch = mutableState.value.pendingWidgetLaunch,
                            pendingWidgetDestination = mutableState.value.pendingWidgetDestination,
                        ).resolveWidgetLaunch()
                    }
                }
                is ProfileRead.Unavailable -> mutableState.value = ProfileScreenState(
                    loading = false, unavailable = read.message,
                    recoveryToken = read.recoveryToken, pendingOperation = read.pendingOperation,
                    destination = ProfileDestination.Profile,
                    pendingWidgetLaunch = mutableState.value.pendingWidgetLaunch,
                    pendingWidgetDestination = mutableState.value.pendingWidgetDestination,
                ).resolveWidgetLaunch()
            }
        }
    }

    fun select(destination: ProfileDestination) {
        var current = mutableState.value
        if (current.busy || current.pendingOperation != null || current.destination == ProfileDestination.Editor) return
        if (current.contactReview != null) return
        if (current.backupReview != null) return
        if (destination !in setOf(
                ProfileDestination.QuickShareSelectInformation,
                ProfileDestination.QuickShareReview,
                ProfileDestination.QuickShareQr,
            ) && (current.quickShareSession != null || current.quickShareError != null)
        ) {
            current = current.copy(quickShareSession = null, quickShareError = null)
            mutableState.value = current
        }
        if (destination != ProfileDestination.QrPresentation && current.shareQrOrigin) {
            current = current.copy(shareQrOrigin = false)
            mutableState.value = current
        }
        if (destination == ProfileDestination.Backup) {
            openBackup()
            return
        }
        if (destination == ProfileDestination.Home || destination == ProfileDestination.QrPresentation) {
            mutableState.value = current.copy(
                destination = destination, contactReview = null, contactError = null, handoffNotice = false,
            )
            refreshContact()
            return
        }
        if (destination == ProfileDestination.Appearance) {
            if (current.saved == null || current.unavailable != null) return
            mutableState.value = current.copy(
                destination = destination, appearanceDraft = current.saved.data.appearance,
                saveError = null, pendingImageLink = false,
            )
            return
        }
        if (destination == ProfileDestination.Editor) {
            if (current.unavailable != null) return
            val draft = current.saved?.data?.toDraft() ?: ProfileDraft.empty()
            mutableState.value = current.copy(
                destination = destination, draft = draft, initialDraft = draft,
                socialPlatformSelections = emptyMap(),
                firstRun = current.saved == null, errors = emptyMap(), saveError = null,
                reviewNormalization = false,
                draftImages = current.savedImages, imageError = null,
                draftKeyStatus = current.keyStatus, keyError = null, keyNotice = null,
                editorCaller = current.destination, editorTab = ProfileEditorTab.Details,
            )
        } else {
            mutableState.value = current.copy(destination = destination)
        }
    }

    fun beginProfileSetup() {
        val current = mutableState.value
        if (current.busy || !current.firstRun || current.saved != null) return
        mutableState.value = current.copy(destination = ProfileDestination.SetupDetails, saveError = null)
    }

    fun advanceProfileSetup() {
        val current = mutableState.value
        if (current.busy || current.saved != null) return
        when (current.destination) {
            ProfileDestination.SetupDetails -> {
                val draft = current.draft ?: return
                val prepared = current.prepareFullNameDraft() ?: return
                if (prepared.generate && prepared.generatedName.isBlank()) {
                    mutableState.value = current.copy(
                        errors = mapOf(ProfileField.FirstName to "Enter at least one name component."),
                        validationAttempt = current.validationAttempt + 1,
                    )
                    return
                }
                val nameErrors = ProfileValidator.validate(prepared.draft).errors.filterKeys {
                    it == ProfileField.FullName || it in structuredNameFields
                }
                if (nameErrors.isNotEmpty()) {
                    mutableState.value = current.copy(
                        errors = nameErrors,
                        validationAttempt = current.validationAttempt + 1,
                    )
                } else {
                    mutableState.value = current.copy(
                        destination = ProfileDestination.SetupContact,
                        errors = emptyMap(), saveError = null,
                    )
                }
            }
            ProfileDestination.SetupContact -> mutableState.value = current.copy(
                destination = ProfileDestination.SetupReview, errors = emptyMap(), saveError = null,
            )
            else -> Unit
        }
    }

    fun skipProfileContact() {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.SetupContact) return
        mutableState.value = current.copy(
            destination = ProfileDestination.SetupReview,
            errors = emptyMap(), saveError = null,
        )
    }

    fun previousProfileSetup() {
        val current = mutableState.value
        if (current.busy) return
        val previous = when (current.destination) {
            ProfileDestination.SetupDetails -> ProfileDestination.Welcome
            ProfileDestination.SetupContact -> ProfileDestination.SetupDetails
            ProfileDestination.SetupReview -> ProfileDestination.SetupContact
            else -> return
        }
        mutableState.value = current.copy(destination = previous)
    }

    fun openEditorSettings(destination: ProfileDestination, contactExportFirst: Boolean = false) {
        require(destination == ProfileDestination.Visibility || destination == ProfileDestination.Security)
        val current = mutableState.value
        if (current.busy || current.unavailable != null || current.firstRun || current.saved == null) return
        val draft = current.saved.data.toDraft()
        mutableState.value = current.copy(
            destination = destination,
            draft = draft,
            initialDraft = draft,
            editorCaller = current.destination,
            editorTab = ProfileEditorTab.Advanced,
            contactExportSettingsFirst = destination == ProfileDestination.Visibility && contactExportFirst,
            errors = emptyMap(),
            saveError = null,
            reviewNormalization = false,
            draftImages = current.savedImages,
            draftKeyStatus = current.keyStatus,
            imageError = null,
            keyError = null,
            keyNotice = null,
        )
    }

    fun openEditorSettingsFromEditor(destination: ProfileDestination) {
        require(destination in setOf(
            ProfileDestination.Visibility, ProfileDestination.Security,
            ProfileDestination.HostedProfile, ProfileDestination.ProfileImages,
        ))
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.Editor) return
        mutableState.value = current.copy(
            destination = destination,
            contactExportSettingsFirst = false,
        )
    }

    fun returnToEditor() {
        val current = mutableState.value
        if (current.busy || current.destination !in setOf(
                ProfileDestination.Visibility, ProfileDestination.Security,
                ProfileDestination.HostedProfile, ProfileDestination.ProfileImages,
            )
        ) return
        mutableState.value = current.copy(
            destination = ProfileDestination.Editor,
            contactExportSettingsFirst = false,
        )
    }

    fun selectEditorTab(tab: ProfileEditorTab) {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.Editor) return
        mutableState.value = current.copy(editorTab = tab)
    }

    fun appearance(value: Appearance) {
        val current = mutableState.value
        if (current.busy) return
        mutableState.value = current.copy(appearanceDraft = value, saveError = null)
    }

    fun selectCardStyle(style: CardStyle) {
        val draft = mutableState.value.appearanceDraft ?: return
        appearance(draft.copy(cardStyle = style))
    }

    fun toggleMinimalItem(item: MinimalItem) {
        val draft = mutableState.value.appearanceDraft ?: return
        appearance(draft.toggleMinimalItem(item))
    }

    fun restoreDefaultAppearance() {
        mutableState.value = mutableState.value.restoreDefaultAppearanceDraft()
    }

    fun cancelAppearance() {
        mutableState.value = mutableState.value.cancelAppearanceDraft()
    }

    fun changeImages() {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.Appearance) return
        if (current.dirty) {
            mutableState.value = current.copy(confirmDiscard = true, pendingImageLink = true)
        } else {
            select(ProfileDestination.Editor)
            mutableState.value = mutableState.value.copy(editorTab = ProfileEditorTab.Advanced)
        }
    }

    fun openBackup() {
        val current = mutableState.value
        if (current.busy || current.pendingOperation != null || current.backupReview != null) return
        if (current.destination == ProfileDestination.Editor && current.dirty) {
            mutableState.value = current.copy(confirmDiscard = true, pendingBackupLink = true)
            return
        }
        if (current.destination != ProfileDestination.Profile && current.destination != ProfileDestination.Editor &&
            current.destination != ProfileDestination.Welcome) return
        mutableState.value = current.copy(
            destination = ProfileDestination.Backup, backupCaller = current.destination,
            backupNotice = null, pendingBackupLink = false,
        )
    }

    private fun staging(): BackupStaging =
        backupStaging ?: throw BackupException("Internal backup staging is unavailable.")

    fun createBackup() {
        val current = mutableState.value
        if (current.busy || current.backupReview != null || current.destination != ProfileDestination.Backup ||
            current.saved == null || current.unavailable != null) return
        mutableState.value = current.copy(backupOperation = BackupOperation.Preparing, backupError = null, backupNotice = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.withFileOperation {
                        val snapshot = repository.backupSnapshot()
                        BackupImageValidation.validate(snapshot.data, snapshot.images)
                        val bytes = BackupArchive.create(snapshot)
                        val stage = bytes.inputStream().use { staging().stage(it) }
                        val date = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(snapshot.capturedAt)
                        stage to "vcard-native-backup-$date.vcardnative.zip"
                    }
                }
                mutableState.value = mutableState.value.copy(
                    backupStage = result.first, backupFileName = result.second,
                    backupOperation = BackupOperation.ReadyForDestination,
                )
            } catch (error: BackupException) {
                mutableState.value = mutableState.value.copy(backupOperation = null, backupError = error.message)
            } catch (error: StoreException) {
                mutableState.value = mutableState.value.copy(backupOperation = null, backupError = error.message)
            }
        }
    }

    fun consumeBackupDestination(): String? {
        val current = mutableState.value
        if (current.backupOperation != BackupOperation.ReadyForDestination) return null
        mutableState.value = current.copy(backupOperation = BackupOperation.ChoosingDestination)
        return current.backupFileName
    }

    fun backupDestinationResult(uri: Uri?, pickerError: String? = null) {
        val current = mutableState.value
        if (current.backupOperation != BackupOperation.ChoosingDestination) return
        val stage = current.backupStage ?: return
        mutableState.value = current.copy(backupOperation = BackupOperation.Writing)
        viewModelScope.launch {
            var notice: String? = null
            var error: String? = pickerError
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) { repository.withFileOperation { writeSelectedBackup(uri, stage) } }
                    notice = "Full application backup write completed. Its filename timestamp is UTC. No cloud synchronization was performed."
                } else if (pickerError == null)
                    notice = "Backup destination selection cancelled. Saved local data is unchanged."
            } catch (failure: BackupException) {
                error = failure.message
            } catch (failure: StoreException) {
                error = failure.message
            } finally {
                try { withContext(Dispatchers.IO) { repository.withFileOperation { staging().discard(stage) } } }
                catch (failure: BackupException) { error = listOfNotNull(error, failure.message).joinToString(" ") }
                catch (failure: StoreException) { error = listOfNotNull(error, failure.message).joinToString(" ") }
                mutableState.value = mutableState.value.copy(
                    backupOperation = null, backupStage = null, backupFileName = null,
                    backupNotice = notice, backupError = error,
                )
            }
        }
    }

    fun startRestorePicker(): Boolean {
        val current = mutableState.value
        if (current.busy || current.pendingOperation != null || current.backupReview != null ||
            current.destination != ProfileDestination.Backup ||
            current.unavailable != null && current.recoveryToken == null) return false
        mutableState.value = current.copy(backupOperation = BackupOperation.ChoosingRestore, backupError = null, backupNotice = null)
        return true
    }

    fun backupPickerFailed() {
        val current = mutableState.value
        if (current.backupOperation == BackupOperation.ChoosingDestination) {
            backupDestinationResult(null, "Android could not open the backup destination picker.")
        } else {
            mutableState.value = current.copy(backupOperation = null, backupError = "Android could not open the backup document picker.")
        }
    }

    fun restoreSelectionResult(uri: Uri?) {
        val current = mutableState.value
        if (current.backupOperation != BackupOperation.ChoosingRestore) return
        if (uri == null) {
            mutableState.value = current.copy(backupOperation = null,
                backupNotice = "Restore document selection cancelled. Saved local data is unchanged.")
            return
        }
        mutableState.value = current.copy(backupOperation = BackupOperation.Validating)
        viewModelScope.launch {
            var stage: StagedBackup? = null
            try {
                val review = withContext(Dispatchers.IO) {
                    repository.withFileOperation {
                        stage = readSelectedBackup(uri)
                        val candidate = BackupArchive.validate(staging().read(requireNotNull(stage)))
                        BackupImageValidation.validate(candidate.data, candidate.images)
                        restoreReviewForRead(requireNotNull(stage), candidate, repository.load())
                    }
                }
                mutableState.value = mutableState.value.copy(backupOperation = null, backupReview = review)
            } catch (error: BackupException) {
                restoreValidationFailed(stage, error.message)
            } catch (error: StoreException) {
                restoreValidationFailed(stage, error.message)
            }
        }
    }

    private suspend fun restoreValidationFailed(stage: StagedBackup?, message: String?) {
        var error = message
        if (stage != null) {
            try { withContext(Dispatchers.IO) { repository.withFileOperation { staging().discard(stage) } } }
            catch (failure: BackupException) { error = listOfNotNull(error, failure.message).joinToString(" ") }
            catch (failure: StoreException) { error = listOfNotNull(error, failure.message).joinToString(" ") }
        }
        mutableState.value = mutableState.value.copy(backupOperation = null, backupReview = null, backupError = error)
    }

    private fun restoreReview(
        stage: StagedBackup, candidate: RestoreCandidate, saved: SavedProfile?, recoveryToken: String? = null,
    ): RestoreReview {
        val keyStatus = candidate.data.profile.publicKey?.let {
            PublicKeyValidator.validateStored(requireNotNull(candidate.publicKey), it)
        }
        return RestoreReview(stage, candidate, saved?.token, saved?.data?.value(ProfileField.FullName), keyStatus, recoveryToken)
    }

    private fun restoreReviewForRead(stage: StagedBackup, candidate: RestoreCandidate, read: ProfileRead): RestoreReview =
        when (read) {
            is ProfileRead.Ready -> restoreReview(stage, candidate, read.saved)
            ProfileRead.Empty -> restoreReview(stage, candidate, null)
            is ProfileRead.Unavailable -> {
                val token = read.recoveryToken ?: throw StoreException(read.message)
                restoreReview(stage, candidate, null, token)
            }
        }

    fun dismissRestoreReview() {
        val current = mutableState.value
        if (current.busy) return
        val review = current.backupReview ?: return
        mutableState.value = current.copy(backupReview = null, backupOperation = BackupOperation.Validating)
        viewModelScope.launch {
            restoreValidationFailed(review.stage, null)
            mutableState.value = mutableState.value.copy(backupNotice = "Restore cancelled. Saved local data is unchanged.")
        }
    }

    fun confirmRestore() {
        val current = mutableState.value
        val review = current.backupReview ?: return
        if (current.busy) return
        qrRequest++
        mutableState.value = current.copy(
            backupOperation = BackupOperation.Committing, backupReview = null, backupError = null, backupNotice = null,
            contactExport = null, contactQr = null, contactReview = null, qrLoading = false,
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.withFileOperation {
                        val candidate = BackupArchive.validate(staging().read(review.stage))
                        BackupImageValidation.validate(candidate.data, candidate.images)
                        val read = repository.load()
                        val freshReview = restoreReviewForRead(review.stage, candidate, read)
                        if (freshReview.expected != review.expected || freshReview.recoveryToken != review.recoveryToken) {
                            return@withFileOperation Triple<SavedProfile?, Map<ImageRole, ImageBitmap>, RestoreReview?>(
                                (read as? ProfileRead.Ready)?.saved,
                                (read as? ProfileRead.Ready)?.saved?.let(::loadImages).orEmpty(),
                                freshReview,
                            )
                        }
                        val snapshot = BackupSnapshot(candidate.data, candidate.images, candidate.publicKey, candidate.createdAt)
                        val restored = if (review.recoveryToken != null) {
                            repository.recover(snapshot, review.recoveryToken, review.stage.sha256)
                        } else repository.restore(snapshot, review.expected)
                        val verified = repository.load()
                        if (verified !is ProfileRead.Ready || verified.saved != restored)
                            throw StoreException("Restore outcome could not be confirmed. Existing recovery evidence is preserved.")
                        Triple(restored, loadImages(restored), null)
                    }
                }
                if (result.third != null) {
                    val status = result.first?.let { withContext(Dispatchers.IO) { loadKeyStatus(it) } }
                    mutableState.value = mutableState.value.copy(
                        saved = result.first, savedImages = result.second, keyStatus = status,
                        backupOperation = null, backupReview = result.third,
                        unavailable = if (result.third?.recoveryToken != null) current.unavailable else null,
                        recoveryToken = result.third?.recoveryToken,
                        backupError = "Saved local data changed after review. Review the replacement again and confirm explicitly.",
                    )
                    return@launch
                }
                val restored = requireNotNull(result.first)
                val status = withContext(Dispatchers.IO) { loadKeyStatus(restored) }
                var cleanupError: String? = null
                try { withContext(Dispatchers.IO) { repository.withFileOperation { staging().discard(review.stage) } } }
                catch (error: BackupException) { cleanupError = error.message }
                catch (error: StoreException) { cleanupError = error.message }
                mutableState.value = ProfileScreenState(
                    loading = false, saved = restored, savedImages = result.second, keyStatus = status,
                    destination = ProfileDestination.Home, backupError = cleanupError,
                    backupNotice = if (review.recoveryToken != null)
                        "Backup recovery completed and the replacement was verified. Previous unavailable data is retained privately; it will not be used automatically."
                    else "Full application restore completed and saved data was verified.",
                    contactCleanupError = current.contactCleanupError,
                )
            } catch (error: com.mackseoul.vcardnative.data.profile.StaleRevisionException) {
                mutableState.value = mutableState.value.copy(
                    backupOperation = null, backupError = "Saved local data changed during confirmation. Choose the backup again.",
                )
                restoreValidationFailed(review.stage, mutableState.value.backupError)
            } catch (error: BackupException) {
                restoreValidationFailed(review.stage, error.message)
            } catch (error: StoreException) {
                val read = withContext(Dispatchers.IO) { repository.load() }
                val unavailable = when (read) {
                    is ProfileRead.Unavailable -> read.message
                    is ProfileRead.Ready -> if (read.saved.token != current.saved?.token)
                        "Saved data changed, but the restored display could not be verified. Reload to inspect the committed state."
                    else null
                    ProfileRead.Empty -> null
                }
                mutableState.value = mutableState.value.copy(
                    unavailable = unavailable, recoveryToken = (read as? ProfileRead.Unavailable)?.recoveryToken,
                    pendingOperation = (read as? ProfileRead.Unavailable)?.pendingOperation,
                    saved = if (unavailable != null) null else mutableState.value.saved,
                    savedImages = if (unavailable != null) emptyMap() else mutableState.value.savedImages,
                    keyStatus = if (unavailable != null) null else mutableState.value.keyStatus,
                )
                restoreValidationFailed(review.stage, error.message)
            }
        }
    }

    fun startImageSelection(role: ImageRole) {
        if (!mutableState.value.busy) {
            mutableState.value = mutableState.value.copy(pickingImage = role, imageError = null)
        }
    }

    fun imageSelectionResult(uri: Uri?) {
        val current = mutableState.value
        val role = current.pickingImage ?: return
        mutableState.value = current.copy(pickingImage = null)
        if (uri == null) return
        mutableState.value = mutableState.value.copy(processingImage = true)
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { readSelectedImage(uri, role) }
                val bitmap = withContext(Dispatchers.IO) { decode(image.bytes) }
                val latest = mutableState.value
                val draft = requireNotNull(latest.draft)
                mutableState.value = latest.copy(
                    processingImage = false,
                    draft = draft.copy(
                        profileImage = if (role == ImageRole.Portrait) image.asset else draft.profileImage,
                        coverImage = if (role == ImageRole.Cover) image.asset else draft.coverImage,
                        pendingImages = draft.pendingImages + (role to image),
                    ),
                    draftImages = latest.draftImages + (role to bitmap),
                    imageError = null,
                )
            } catch (error: ImageException) {
                mutableState.value = mutableState.value.copy(processingImage = false, imageError = error.message)
            } catch (error: StoreException) {
                mutableState.value = mutableState.value.copy(processingImage = false, imageError = error.message)
            }
        }
    }

    fun removeImage(role: ImageRole) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy) return
        mutableState.value = current.copy(
            draft = draft.copy(
                profileImage = if (role == ImageRole.Portrait) null else draft.profileImage,
                coverImage = if (role == ImageRole.Cover) null else draft.coverImage,
                pendingImages = draft.pendingImages - role,
            ),
            draftImages = current.draftImages - role, imageError = null,
        )
    }

    fun startKeyImport() {
        val current = mutableState.value
        if (current.busy || current.destination !in setOf(ProfileDestination.Editor, ProfileDestination.Security) ||
            current.unavailable != null) return
        mutableState.value = current.copy(pickingKey = true, keyError = null, keyNotice = null)
    }

    fun keyPickerFailed() {
        mutableState.value = mutableState.value.copy(
            pickingKey = false, keySaveToken = null,
            keyError = "Android could not open the document picker. Your saved key and draft are unchanged.",
        )
    }

    fun keyImportResult(uri: Uri?) {
        val current = mutableState.value
        if (!current.pickingKey) return
        mutableState.value = current.copy(pickingKey = false)
        if (uri == null) return
        mutableState.value = mutableState.value.copy(processingKey = true)
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val raw = readSelectedPublicKey(uri)
                    try { PublicKeyValidator.import(raw) } finally { raw.fill(0) }
                }
                val status = withContext(Dispatchers.IO) {
                    PublicKeyValidator.validateStored(imported.bytes, imported.asset)
                }
                val latest = mutableState.value
                val draft = requireNotNull(latest.draft)
                mutableState.value = latest.copy(
                    processingKey = false, keyError = null, draftKeyStatus = status,
                    draft = draft.copy(publicKey = imported.asset, pendingPublicKey = imported),
                    errors = latest.errors - ProfileField.PublicKey, reviewNormalization = false,
                )
            } catch (error: PublicKeyException) {
                mutableState.value = mutableState.value.copy(processingKey = false, keyError = error.message)
            }
        }
    }

    fun removePublicKey() {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy || current.unavailable != null) return
        mutableState.value = current.copy(
            draft = draft.copy(publicKey = null, pendingPublicKey = null),
            draftKeyStatus = null, keyError = null, keyNotice = null,
        )
    }

    private fun loadKeyStatus(saved: SavedProfile): PublicKeyStatus? {
        val asset = saved.data.profile.publicKey ?: return null
        try {
            return PublicKeyValidator.validateStored(repository.readPublicKey(asset), asset)
        } catch (error: PublicKeyException) {
            throw StoreException(error.message ?: "The saved public key could not be validated.")
        }
    }

    fun refreshPublicKeyStatus() {
        val current = mutableState.value
        val saved = current.saved ?: return
        if (current.busy || current.backupReview != null || saved.data.profile.publicKey == null || current.unavailable != null) return
        mutableState.value = current.copy(processingKey = true, keyStatus = null)
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { loadKeyStatus(saved) }
                mutableState.value = mutableState.value.copy(processingKey = false, keyStatus = status)
            } catch (error: StoreException) {
                mutableState.value = mutableState.value.copy(
                    processingKey = false, keyStatus = null, keyError = error.message, unavailable = error.message,
                )
            }
        }
    }

    fun startPublicKeySave(): Boolean {
        val current = mutableState.value
        val saved = current.saved
        if (current.busy || saved == null || current.destination != ProfileDestination.MyCard ||
            current.unavailable != null || saved.data.visibility(ProfileField.PublicKey).showOnCard != true ||
            current.keyStatus?.usable != true) return false
        mutableState.value = current.copy(keySaveToken = saved.token, keyError = null, keyNotice = null)
        return true
    }

    fun publicKeySaveResult(uri: Uri?, write: (Uri, PublicKeyExport) -> Unit) {
        val current = mutableState.value
        val token = current.keySaveToken ?: return
        mutableState.value = current.copy(keySaveToken = null)
        if (uri != null) handoffPublicKey(token, "Public-key file write completed.") { write(uri, it) }
    }

    fun sharePublicKey(share: (PublicKeyExport) -> Unit) {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.MyCard || current.unavailable != null) return
        val saved = current.saved ?: return
        handoffPublicKey(saved.token, "Android public-key sharing opened. Delivery or saving is not confirmed.", share)
    }

    private fun handoffPublicKey(token: RevisionToken, notice: String, action: (PublicKeyExport) -> Unit) {
        mutableState.value = mutableState.value.copy(handingOffKey = true, keyError = null, keyNotice = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(token) { saved ->
                        val asset = saved.data.profile.publicKey
                            ?: throw PublicKeyException("No saved public key is available.")
                        if (saved.data.visibility(ProfileField.PublicKey).showOnCard != true)
                            throw PublicKeyException("The saved public key is hidden from My Card.")
                        val bytes = repository.readPublicKey(asset)
                        val status = PublicKeyValidator.validateStored(bytes, asset)
                        if (!status.usable)
                            throw PublicKeyException(status.explanation ?: "The public key is not currently usable.")
                        action(PublicKeyExport(asset, bytes))
                    }
                }
                mutableState.value = mutableState.value.copy(handingOffKey = false, keyNotice = notice)
            } catch (error: PublicKeyException) {
                mutableState.value = mutableState.value.copy(handingOffKey = false, keyError = error.message)
            } catch (error: StoreException) {
                mutableState.value = mutableState.value.copy(
                    handingOffKey = false, keyStatus = null, keyError = error.message, unavailable = error.message,
                )
            }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw StoreException("The verified image could not be displayed. Saved data is preserved.")
        return bitmap.asImageBitmap()
    }

    private fun loadImages(saved: SavedProfile): Map<ImageRole, ImageBitmap> = buildMap {
        fun load(role: ImageRole, asset: ImageAsset?) {
            if (asset != null) put(role, decode(repository.readImage(asset)))
        }
        load(ImageRole.Portrait, saved.data.profile.profileImage)
        load(ImageRole.Cover, saved.data.profile.coverImage)
    }

    fun edit(field: ProfileField, value: String) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy) return
        val updatedDraft = when {
            field in legacySocialFields -> draft.updateLegacySocialValue(field, value)
            field == ProfileField.Phone -> {
            val parsed = PhoneNumbers.parse(value.trim(), draft.phoneRegion)
            val region = if (value.trimStart().startsWith("+")) parsed?.region ?: draft.phoneRegion
            else draft.phoneRegion
            draft.copy(
                values = draft.values + (field to value),
                phoneRegion = region,
                phoneNumberE164 = parsed?.e164,
                allowUnparsedLegacyPhone = false,
            )
            }
            else -> draft.copy(values = draft.values + (field to value))
        }
        mutableState.value = current.copy(
            draft = updatedDraft,
            errors = current.errors - field, saveError = null, reviewNormalization = false,
        )
    }

    fun selectPhoneRegion(region: String) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy || !PhoneNumbers.isSupportedRegion(region)) return
        val parsed = PhoneNumbers.parse(draft.values[ProfileField.Phone].orEmpty(), region)
        mutableState.value = current.copy(
            draft = draft.copy(
                phoneRegion = region,
                phoneNumberE164 = parsed?.e164,
                allowUnparsedLegacyPhone = false,
            ),
            errors = current.errors - ProfileField.Phone,
            saveError = null,
            reviewNormalization = false,
        )
    }

    fun addSocialProfile() {
        val draft = mutableState.value.draft ?: return
        val entry = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            java.util.UUID.randomUUID(), "", "", "", true, false,
        )
        updateSocialDraft(draft.socialProfiles + entry)
    }

    fun editSocialProfile(entry: com.mackseoul.vcardnative.domain.profile.SocialProfile) {
        val draft = mutableState.value.draft ?: return
        updateSocialDraft(draft.socialProfiles.map { if (it.id == entry.id) entry else it })
    }

    fun removeSocialProfile(id: java.util.UUID) {
        val draft = mutableState.value.draft ?: return
        updateSocialDraft(draft.socialProfiles.filterNot { it.id == id })
    }

    fun selectSocialPlatform(id: java.util.UUID, option: SocialPlatformOption) {
        val current = mutableState.value
        if (current.busy || current.unavailable != null) return
        val entry = current.draft?.socialProfiles?.firstOrNull { it.id == id } ?: return
        val previous = current.socialPlatformSelections[id] ?: SocialPlatformSelection.fromSaved(entry.platform)
        val selection = previous.select(option)
        editSocialProfile(selection.applyTo(entry, previous))
        mutableState.value = mutableState.value.copy(
            socialPlatformSelections = mutableState.value.socialPlatformSelections + (id to selection),
        )
    }

    fun editCustomSocialPlatform(id: java.util.UUID, name: String) {
        val current = mutableState.value
        if (current.busy || current.unavailable != null) return
        val entry = current.draft?.socialProfiles?.firstOrNull { it.id == id } ?: return
        val previous = current.socialPlatformSelections[id] ?: SocialPlatformSelection.fromSaved(entry.platform)
        val selection = SocialPlatformSelection(SocialPlatformOption.Other, name)
        editSocialProfile(selection.applyTo(entry, previous))
        mutableState.value = mutableState.value.copy(
            socialPlatformSelections = mutableState.value.socialPlatformSelections + (id to selection),
        )
    }

    fun moveSocialProfile(id: java.util.UUID, offset: Int) {
        val draft = mutableState.value.draft ?: return
        updateSocialDraft(draft.moveSocialProfile(id, offset).socialProfiles)
    }

    private fun updateSocialDraft(entries: List<com.mackseoul.vcardnative.domain.profile.SocialProfile>) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy || current.unavailable != null) return
        mutableState.value = current.copy(
            draft = draft.withSocialProfiles(entries),
            socialPlatformSelections = current.socialPlatformSelections.filterKeys { id -> entries.any { it.id == id } },
            errors = current.errors - legacySocialFields.toSet(), saveError = null, reviewNormalization = false,
        )
    }

    fun includeCountryCodeWhenSharing(include: Boolean) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy) return
        mutableState.value = current.copy(
            draft = draft.copy(includeCountryCodeWhenSharing = include),
            saveError = null,
            reviewNormalization = false,
        )
    }

    fun privacy(field: ProfileField, visibility: Visibility) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.busy) return
        mutableState.value = current.copy(
            draft = if (field in legacySocialFields) draft.updateLegacySocialVisibility(field, visibility)
            else draft.copy(visibility = draft.visibility + (field to visibility)),
            saveError = null, reviewNormalization = false,
        )
    }

    fun save() {
        val current = mutableState.value
        if (current.draft == null || current.busy || current.unavailable != null) return
        val prepared = current.prepareFullNameDraft() ?: return
        if (prepared.generate && prepared.generatedName.isBlank()) {
            mutableState.value = current.copy(
                errors = mapOf(ProfileField.FirstName to "Enter at least one name component."),
                editorTab = ProfileEditorTab.Details,
                reviewNormalization = false,
                validationAttempt = current.validationAttempt + 1,
            )
            return
        }
        val draft = prepared.draft
        val validation = ProfileValidator.validate(draft)
        val normalized = validation.normalized
        if (normalized == null || validation.errors.isNotEmpty()) {
            val firstInvalid = validation.errors.keys.firstOrNull()
            val destination = when {
                current.firstRun && firstInvalid != null && firstInvalid.editorTab() == ProfileEditorTab.Details ->
                    ProfileDestination.SetupDetails
                current.firstRun && firstInvalid != null && firstInvalid.editorTab() == ProfileEditorTab.Contact ->
                    ProfileDestination.SetupContact
                else -> current.destination
            }
            mutableState.value = current.copy(
                destination = destination,
                editorTab = firstInvalid?.editorTab() ?: current.editorTab,
                errors = validation.errors, reviewNormalization = false,
                validationAttempt = current.validationAttempt + 1,
            )
            return
        }
        if (normalized.copy(phoneNumberE164 = draft.phoneNumberE164) != draft) {
            mutableState.value = current.copy(draft = normalized, errors = emptyMap(), reviewNormalization = true)
            return
        }
        mutableState.value = current.copy(saving = true, errors = emptyMap(), saveError = null)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.save(normalized, current.saved?.token) }
                val images = withContext(Dispatchers.IO) { loadImages(saved) }
                val keyStatus = withContext(Dispatchers.IO) { loadKeyStatus(saved) }
                mutableState.value = ProfileScreenState(
                    loading = false, saved = saved, savedImages = images, keyStatus = keyStatus,
                    destination = if (current.firstRun) ProfileDestination.Home else current.editorCaller,
                    appearanceDraft = saved.data.appearance.takeIf { current.editorCaller == ProfileDestination.Appearance },
                    contactCleanupError = current.contactCleanupError,
                )
                if (mutableState.value.destination == ProfileDestination.Home) refreshContact()
            } catch (error: StoreException) {
                val read = withContext(Dispatchers.IO) { repository.load() }
                mutableState.value = if (read is ProfileRead.Unavailable) ProfileScreenState(
                    loading = false, destination = ProfileDestination.Profile, saveError = error.message,
                    unavailable = read.message, recoveryToken = read.recoveryToken,
                    pendingOperation = read.pendingOperation,
                    draft = current.draft, initialDraft = current.initialDraft,
                    draftImages = current.draftImages, draftKeyStatus = current.draftKeyStatus,
                    saved = current.saved.takeIf { read.pendingOperation == null },
                    savedImages = current.savedImages.takeIf { read.pendingOperation == null } ?: emptyMap(),
                ) else mutableState.value.copy(
                    saving = false, saveError = error.message,
                )
            }
        }
    }

    fun saveAppearance() {
        val current = mutableState.value
        val saved = current.saved ?: return
        val appearance = current.appearanceDraft ?: return
        if (current.busy || current.unavailable != null || !current.dirty) return
        mutableState.value = current.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    repository.save(saved.data.toDraft().copy(appearance = appearance), saved.token)
                }
                mutableState.value = ProfileScreenState(
                    loading = false, saved = updated, savedImages = current.savedImages, keyStatus = current.keyStatus,
                    destination = ProfileDestination.Profile,
                    contactCleanupError = current.contactCleanupError,
                )
            } catch (error: StoreException) {
                val read = withContext(Dispatchers.IO) { repository.load() }
                mutableState.value = if (read is ProfileRead.Unavailable) ProfileScreenState(
                    loading = false, destination = ProfileDestination.Profile, saveError = error.message,
                    unavailable = read.message, recoveryToken = read.recoveryToken,
                    pendingOperation = read.pendingOperation,
                ) else mutableState.value.copy(
                    saving = false, saveError = error.message,
                )
            }
        }
    }

    fun requestBack(exit: () -> Unit) {
        val current = mutableState.value
        if (current.busy) return
        if (current.pendingOperation != null) {
            exit()
            return
        }
        if (current.backupReview != null) {
            dismissRestoreReview()
            return
        }
        if (current.contactReview != null) {
            dismissContactReview()
            return
        }
        when (current.destination) {
            ProfileDestination.QuickShareSelectInformation -> {
                cancelQuickShare()
                return
            }
            ProfileDestination.QuickShareReview -> {
                backToQuickShareSelection()
                return
            }
            ProfileDestination.QuickShareQr -> {
                backToQuickShareReview()
                return
            }
            ProfileDestination.Welcome -> exit()
            ProfileDestination.SetupDetails,
            ProfileDestination.SetupContact,
            ProfileDestination.SetupReview -> previousProfileSetup()
            ProfileDestination.Visibility,
            ProfileDestination.Security,
            ProfileDestination.HostedProfile,
            ProfileDestination.ProfileImages -> returnToEditor()
            ProfileDestination.Editor -> if (current.dirty) {
                mutableState.value = current.copy(confirmDiscard = true)
            } else leaveEditor(exit)
            ProfileDestination.Profile -> mutableState.value = current.copy(destination = ProfileDestination.Home)
            ProfileDestination.Home -> exit()
            ProfileDestination.OnlineCardQr -> mutableState.value = current.copy(destination = ProfileDestination.MyCard)
            ProfileDestination.QrPresentation -> when {
                current.shortcutQrOrigin -> exit()
                current.shareQrOrigin -> mutableState.value = current.copy(
                    destination = ProfileDestination.Home, shareQrOrigin = false,
                )
                else -> mutableState.value = current.copy(destination = ProfileDestination.MyCard)
            }
            ProfileDestination.MyCard -> mutableState.value = current.copy(destination = ProfileDestination.Home)
            ProfileDestination.Appearance -> if (current.dirty) {
                mutableState.value = current.copy(confirmDiscard = true, pendingImageLink = false)
            } else leaveEditor(exit)
            ProfileDestination.Backup -> mutableState.value = current.copy(
                destination = current.backupCaller, backupNotice = null,
            )
        }
    }

    fun keepEditing() {
        mutableState.value = mutableState.value.copy(
            confirmDiscard = false, pendingImageLink = false, pendingBackupLink = false,
            pendingShortcutQr = false, pendingWidgetLaunch = false,
            pendingWidgetDestination = QuickCardWidgetMenuDestination.Share,
        )
    }

    fun leaveEditor(exit: () -> Unit) {
        val current = mutableState.value
        if (current.pendingWidgetLaunch) {
            mutableState.value = current.copy(
                draft = current.initialDraft, draftImages = current.savedImages,
                draftKeyStatus = current.keyStatus, appearanceDraft = current.saved?.data?.appearance,
                confirmDiscard = false, pendingImageLink = false, pendingBackupLink = false,
                errors = emptyMap(), saveError = null, reviewNormalization = false,
                imageError = null, keyError = null, socialPlatformSelections = emptyMap(),
            ).resolveWidgetLaunch()
            if (mutableState.value.shouldRefreshContact) refreshContact()
            return
        }
        if (current.pendingShortcutQr) {
            if (current.saved != null && current.unavailable == null) {
                openShortcutQr(current)
            } else {
                val emptyDraft = ProfileDraft.empty()
                mutableState.value = current.copy(
                    destination = if (current.firstRun) ProfileDestination.Welcome else current.editorCaller,
                    draft = emptyDraft.takeIf { current.firstRun },
                    initialDraft = emptyDraft.takeIf { current.firstRun },
                    confirmDiscard = false, pendingShortcutQr = false, shortcutQrOrigin = false, shareQrOrigin = false,
                    errors = emptyMap(), saveError = null, reviewNormalization = false,
                    draftImages = emptyMap(), imageError = null, draftKeyStatus = null,
                    keyError = null, keyNotice = null, appearanceDraft = current.saved?.data?.appearance,
                )
            }
            return
        }
        if (current.pendingBackupLink) {
            val draft = current.saved?.data?.toDraft() ?: ProfileDraft.empty()
            mutableState.value = current.copy(
                destination = ProfileDestination.Backup, backupCaller = ProfileDestination.Editor,
                draft = draft, initialDraft = draft, confirmDiscard = false, pendingBackupLink = false,
                draftImages = current.savedImages, draftKeyStatus = current.keyStatus,
                errors = emptyMap(), saveError = null, imageError = null, keyError = null,
            )
            return
        }
        if (current.destination == ProfileDestination.Appearance) {
            val next = current.copy(
                appearanceDraft = current.saved?.data?.appearance, confirmDiscard = false,
                pendingImageLink = false, saveError = null,
            )
            mutableState.value = next
            if (current.pendingImageLink) {
                select(ProfileDestination.Editor)
                mutableState.value = mutableState.value.copy(editorTab = ProfileEditorTab.Advanced)
            }
            else mutableState.value = next.copy(destination = ProfileDestination.Profile)
            return
        }
        if (current.firstRun) exit() else {
            mutableState.value = current.copy(
                destination = current.editorCaller, draft = null, initialDraft = null,
                errors = emptyMap(), saveError = null, confirmDiscard = false, reviewNormalization = false,
                draftImages = emptyMap(), imageError = null, draftKeyStatus = null, keyError = null, keyNotice = null,
            )
            if (current.editorCaller == ProfileDestination.Home) refreshContact()
        }
    }

    private fun refreshContact() {
        val current = mutableState.value
        val saved = current.saved ?: return
        val request = ++qrRequest
        mutableState.value = current.copy(
            contactExport = null, contactQr = null, qrFailure = null, qrLoading = true,
        )
        viewModelScope.launch {
            fun stillCurrent() = request == qrRequest && mutableState.value.saved?.token == saved.token &&
                mutableState.value.destination in setOf(ProfileDestination.Home, ProfileDestination.QrPresentation)
            try {
                val export = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(saved.token) { ContactExport.from(it.data.profile) }
                }
                var failure: QrFailure? = null
                val qr = withContext(Dispatchers.Default) {
                    try { ContactQrEncoder.encode(export) } catch (error: ContactQrException) {
                        failure = error.failure
                        null
                    }
                }
                if (stillCurrent()) mutableState.value = mutableState.value.copy(
                    contactExport = export, contactQr = qr, qrFailure = failure, qrLoading = false,
                )
            } catch (error: ContactExportException) {
                if (stillCurrent()) mutableState.value = mutableState.value.copy(
                    contactExport = null, contactQr = null, qrLoading = false, contactError = error.message,
                )
            } catch (error: StoreException) {
                if (stillCurrent()) mutableState.value = mutableState.value.copy(
                    contactExport = null, contactQr = null, qrLoading = false, contactError = error.message,
                )
            }
        }
    }

    fun reviewContact() {
        val current = mutableState.value
        val saved = current.saved ?: return
        if (current.busy || current.unavailable != null || current.destination != ProfileDestination.Home) return
        mutableState.value = current.copy(exporting = true, contactError = null, handoffNotice = false)
        viewModelScope.launch {
            try {
                val review = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(saved.token) {
                        ContactReview(it.token, ContactExport.from(it.data.profile))
                    }
                }
                mutableState.value = mutableState.value.copy(
                    exporting = false, contactExport = review.export, contactReview = review,
                )
            } catch (error: StoreException) {
                qrRequest++
                mutableState.value = mutableState.value.copy(
                    exporting = false, contactError = error.message, contactExport = null, contactQr = null, qrLoading = false,
                )
            } catch (error: ContactExportException) {
                qrRequest++
                mutableState.value = mutableState.value.copy(
                    exporting = false, contactError = error.message, contactExport = null, contactQr = null, qrLoading = false,
                )
            }
        }
    }

    fun dismissContactReview() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(contactReview = null)
    }

    fun editExportSelections() {
        if (mutableState.value.busy) return
        dismissContactReview()
        openEditorSettings(ProfileDestination.Visibility, contactExportFirst = true)
    }

    fun shareContact(share: (ContactExport) -> Unit) {
        val current = mutableState.value
        val saved = current.saved ?: return
        if (current.busy || current.unavailable != null || current.destination != ProfileDestination.Home) return
        mutableState.value = current.copy(exporting = true, contactError = null, handoffNotice = false)
        viewModelScope.launch {
            try {
                val export = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(saved.token) {
                        ContactExport.from(it.data.profile)
                    }
                }
                repository.withSavedSnapshot(saved.token) { share(export) }
                mutableState.value = mutableState.value.copy(exporting = false, handoffNotice = true)
            } catch (error: StoreException) {
                mutableState.value = mutableState.value.copy(
                    exporting = false, contactError = error.message,
                )
            } catch (error: ContactExportException) {
                mutableState.value = mutableState.value.copy(exporting = false, contactError = error.message)
            }
        }
    }

    fun showCardQr() {
        if (mutableState.value.destination != ProfileDestination.MyCard || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(shareQrOrigin = false)
        select(ProfileDestination.QrPresentation)
    }

    fun showShareQr() {
        val current = mutableState.value
        if (current.destination != ProfileDestination.Home || current.busy || current.loading ||
            current.saved == null || current.unavailable != null || current.pendingOperation != null ||
            current.contactReview != null || current.backupReview != null || current.contactQr == null
        ) return
        mutableState.value = current.copy(shareQrOrigin = true, shortcutQrOrigin = false)
        select(ProfileDestination.QrPresentation)
        if (mutableState.value.destination != ProfileDestination.QrPresentation) {
            mutableState.value = mutableState.value.copy(shareQrOrigin = false)
        }
    }

    fun showOnlineCardQr() {
        mutableState.value = mutableState.value.openOnlineCardQr()
    }

    fun requestPrimaryQrFromShortcut() {
        var current = mutableState.value
        if (current.destination == ProfileDestination.QrPresentation && current.shortcutQrOrigin) return
        if (current.pendingShortcutQr) return
        if (current.pendingWidgetLaunch) current = current.copy(
            pendingWidgetLaunch = false,
            pendingWidgetDestination = QuickCardWidgetMenuDestination.Share,
        )
        mutableState.value = current.copy(
            pendingShortcutQr = true,
            pendingImageLink = false,
            pendingBackupLink = false,
        )
        resolvePendingShortcutQr()
    }

    fun requestMyCardFromWidget() {
        requestDestinationFromWidget(QuickCardWidgetMenuDestination.Share)
    }

    fun requestDestinationFromWidget(destination: QuickCardWidgetMenuDestination) {
        val current = mutableState.value
        if (current.pendingWidgetLaunch) return
        mutableState.value = current.copy(pendingWidgetLaunch = true, pendingShortcutQr = false,
            pendingWidgetDestination = destination)
        resolvePendingWidgetLaunch()
    }

    fun resolvePendingWidgetLaunch() {
        val current = mutableState.value
        val next = current.resolveWidgetLaunch()
        if (next == current) return
        mutableState.value = next
        if (current.pendingWidgetLaunch && !next.pendingWidgetLaunch && next.shouldRefreshContact) refreshContact()
    }

    fun resolvePendingShortcutQr() {
        val current = mutableState.value
        if (!current.pendingShortcutQr || current.loading || current.busy) return
        if (current.dirty && (current.draft != null || current.destination == ProfileDestination.Appearance)) {
            if (!current.confirmDiscard) {
                mutableState.value = current.copy(confirmDiscard = true, pendingImageLink = false)
            }
            return
        }
        if (current.saved == null || current.unavailable != null) {
            mutableState.value = current.copy(pendingShortcutQr = false)
            return
        }
        openShortcutQr(current)
    }

    fun closeQrPresentation(exit: () -> Unit) {
        val current = mutableState.value
        if (current.destination != ProfileDestination.QrPresentation) return
        when {
            current.shortcutQrOrigin -> exit()
            current.shareQrOrigin -> select(ProfileDestination.Home)
            else -> select(ProfileDestination.MyCard)
        }
    }

    private fun openShortcutQr(current: ProfileScreenState) {
        if (current.saved == null || current.unavailable != null) {
            mutableState.value = current.copy(pendingShortcutQr = false)
            return
        }
        mutableState.value = current.copy(
            destination = ProfileDestination.QrPresentation,
            draft = null, initialDraft = null, errors = emptyMap(), saveError = null,
            reviewNormalization = false, confirmDiscard = false, appearanceDraft = null,
            pendingImageLink = false, draftImages = emptyMap(), imageError = null,
            draftKeyStatus = null, keyError = null, keyNotice = null,
            contactReview = null, contactError = null, handoffNotice = false,
            quickShareSession = null, quickShareError = null,
            shortcutQrOrigin = true, shareQrOrigin = false, pendingShortcutQr = false,
            pendingWidgetLaunch = false,
            pendingWidgetDestination = QuickCardWidgetMenuDestination.Share,
            pendingBackupLink = false,
        )
        refreshContact()
    }

    fun startQuickShare() {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.MyCard || current.unavailable != null) return
        val saved = current.saved
        if (saved == null) {
            mutableState.value = current.copy(quickShareError = "Save your profile before using Custom Share.")
            return
        }
        val selectionStore = quickShareSelectionStore
        if (selectionStore == null) {
            mutableState.value = current.copy(quickShareError = "Custom Share selection storage is unavailable.")
            return
        }

        val request = ++quickShareRequest
        mutableState.value = current.copy(exporting = true, quickShareSession = null, quickShareError = null)
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(saved.token) { it }
                }
                val confirmedKeys = withContext(Dispatchers.IO) { selectionStore.readConfirmedKeys() }
                val profile = snapshot.data.profile
                val fullName = profile.values[ProfileField.FullName]?.takeIf { it.isNotBlank() }
                val session = QuickShareSessionState(
                    savedProfile = snapshot,
                    fullName = fullName,
                    availableFields = QuickShareSelection.availableFields(profile.values),
                    selectedFields = QuickShareSelection.restoreSelection(profile.values, confirmedKeys),
                    error = if (fullName == null) "A saved Full Name is required before sharing." else null,
                )
                if (request != quickShareRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = ProfileDestination.QuickShareSelectInformation,
                    quickShareSession = session,
                    quickShareError = null,
                )
            } catch (error: Exception) {
                if (request != quickShareRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = ProfileDestination.MyCard,
                    quickShareSession = null,
                    quickShareError = error.message ?: "Custom Share could not load the saved profile.",
                )
            }
        }
    }

    fun toggleQuickShareField(field: ProfileField) {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareSelectInformation ||
            field !in session.availableFields
        ) return
        val proposed = session.selectedFields.toMutableSet().apply {
            if (!add(field)) remove(field)
        }
        updateQuickShareDraft(current, session, proposed)
    }

    fun selectAllQuickShareFields() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareSelectInformation) return
        updateQuickShareDraft(
            current,
            session,
            QuickShareSelection.selectAll(session.savedProfile.data.profile.values),
        )
    }

    fun clearQuickShareFields() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareSelectInformation) return
        updateQuickShareDraft(current, session, QuickShareSelection.clearAll())
    }

    private fun updateQuickShareDraft(
        current: ProfileScreenState,
        session: QuickShareSessionState,
        proposedSelection: Set<ProfileField>,
    ) {
        val values = session.savedProfile.data.profile.values
        val selected = QuickShareSelection.restoreSelection(
            values,
            proposedSelection.mapTo(linkedSetOf()) { it.key },
        )
        mutableState.value = current.copy(
            quickShareSession = session.copy(
                selectedFields = selected,
                qrResult = null,
                error = if (session.fullName == null) "A saved Full Name is required before sharing." else null,
                selectionPersistenceError = null,
                pendingContactHandoff = null,
            ),
        )
    }

    fun continueQuickShareToReview() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareSelectInformation) return
        val error = when {
            session.fullName.isNullOrBlank() -> "A saved Full Name is required before sharing."
            !QuickShareSelection.canContinue(session.savedProfile.data.profile.values, session.selectedFields) ->
                "Select at least one populated additional field."
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(quickShareSession = session.copy(error = error))
            return
        }
        mutableState.value = current.copy(
            destination = ProfileDestination.QuickShareReview,
            quickShareSession = session.copy(error = null, qrResult = null, pendingContactHandoff = null),
        )
    }

    fun backToQuickShareSelection() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareReview) return
        mutableState.value = current.copy(
            destination = ProfileDestination.QuickShareSelectInformation,
            quickShareSession = session.copy(error = null, qrResult = null, pendingContactHandoff = null),
        )
    }

    fun backToQuickShareReview() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareQr) return
        mutableState.value = current.copy(
            destination = ProfileDestination.QuickShareReview,
            quickShareSession = session.copy(qrResult = null, pendingContactHandoff = null),
        )
    }

    fun cancelQuickShare() {
        val current = mutableState.value
        if (current.busy || current.quickShareSession == null) return
        quickShareRequest++
        mutableState.value = current.copy(
            destination = ProfileDestination.MyCard,
            quickShareSession = null,
            quickShareError = null,
        )
    }

    fun doneQuickShare() = cancelQuickShare()

    fun generateQuickShareQr() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareReview ||
            session.pendingContactHandoff != null
        ) return
        if (session.fullName.isNullOrBlank() ||
            !QuickShareSelection.canContinue(session.savedProfile.data.profile.values, session.selectedFields)
        ) {
            mutableState.value = current.copy(
                quickShareSession = session.copy(error = "Select at least one populated field and confirm the saved Full Name."),
            )
            return
        }
        val selectionStore = quickShareSelectionStore
        if (selectionStore == null) {
            mutableState.value = current.copy(
                quickShareSession = session.copy(selectionPersistenceError = "Custom Share selection storage is unavailable."),
            )
            return
        }

        val request = ++quickShareRequest
        val persistedKeys = confirmedKeys(session.selectedFields)
        mutableState.value = current.copy(
            exporting = true,
            quickShareSession = session.copy(
                qrResult = null,
                error = null,
                selectionPersistenceError = null,
                pendingContactHandoff = null,
            ),
        )
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(session.savedProfile.token) { it }
                }
                val profile = snapshot.data.profile
                val selection = QuickShareSelection.restoreSelection(profile.values, persistedKeys)
                val result = withContext(Dispatchers.Default) { generateQuickShareQr(profile, selection) }
                if (request != quickShareRequest) return@launch
                if (result is QuickShareQrResult.ExportUnavailable) {
                    mutableState.value = mutableState.value.copy(
                        exporting = false,
                        destination = ProfileDestination.QuickShareReview,
                        quickShareSession = session.copy(
                            savedProfile = snapshot,
                            fullName = profile.values[ProfileField.FullName]?.takeIf { it.isNotBlank() },
                            availableFields = QuickShareSelection.availableFields(profile.values),
                            selectedFields = selection,
                            qrResult = result,
                            error = result.error.message ?: "The filtered contact could not be prepared.",
                            pendingContactHandoff = null,
                        ),
                    )
                    return@launch
                }

                val persistenceError = try {
                    withContext(Dispatchers.IO) {
                        repository.withSavedSnapshot(snapshot.token) {
                            try {
                                selectionStore.replaceConfirmedKeys(persistedKeys)
                            } catch (error: Exception) {
                                throw QuickShareSelectionWriteFailure(error)
                            }
                        }
                    }
                    null
                } catch (error: QuickShareSelectionWriteFailure) {
                    error.cause?.message ?: "Custom Share selection could not be remembered."
                }
                if (request != quickShareRequest) return@launch
                val destination = if (result is QuickShareQrResult.QrAvailable) {
                    ProfileDestination.QuickShareQr
                } else {
                    ProfileDestination.QuickShareReview
                }
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = destination,
                    quickShareSession = session.copy(
                        savedProfile = snapshot,
                        fullName = profile.values[ProfileField.FullName]?.takeIf { it.isNotBlank() },
                        availableFields = QuickShareSelection.availableFields(profile.values),
                        selectedFields = selection,
                        qrResult = result,
                        selectionPersistenceError = persistenceError,
                        pendingContactHandoff = null,
                    ),
                )
            } catch (error: StoreException) {
                if (request != quickShareRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = ProfileDestination.QuickShareReview,
                    quickShareSession = session.copy(
                        qrResult = null,
                        error = error.message ?: "The saved profile changed. Review it again before sharing.",
                        pendingContactHandoff = null,
                    ),
                )
            } catch (error: Exception) {
                if (request != quickShareRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = ProfileDestination.QuickShareReview,
                    quickShareSession = session.copy(
                        qrResult = null,
                        error = error.message ?: "Custom Share QR could not be generated.",
                        pendingContactHandoff = null,
                    ),
                )
            }
        }
    }

    fun initiateQuickShareContactShare() {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.busy || current.destination != ProfileDestination.QuickShareReview ||
            session.pendingContactHandoff != null
        ) return
        if (session.fullName.isNullOrBlank() ||
            !QuickShareSelection.canContinue(session.savedProfile.data.profile.values, session.selectedFields)
        ) {
            mutableState.value = current.copy(
                quickShareSession = session.copy(error = "Select at least one populated field and confirm the saved Full Name."),
            )
            return
        }
        val selectionStore = quickShareSelectionStore
        if (selectionStore == null) {
            mutableState.value = current.copy(
                quickShareSession = session.copy(selectionPersistenceError = "Custom Share selection storage is unavailable."),
            )
            return
        }

        val handoffId = ++nextQuickShareHandoffId
        mutableState.value = current.copy(
            exporting = true,
            quickShareSession = session.copy(error = null, selectionPersistenceError = null),
        )
        viewModelScope.launch {
            try {
                val handoff = withContext(Dispatchers.IO) {
                    repository.withSavedSnapshot(session.savedProfile.token) { snapshot ->
                        val profile = snapshot.data.profile
                        val selection = QuickShareSelection.restoreSelection(
                            profile.values,
                            confirmedKeys(session.selectedFields),
                        )
                        if (!QuickShareSelection.canContinue(profile.values, selection) ||
                            profile.values[ProfileField.FullName].isNullOrBlank()
                        ) {
                            throw ContactExportException("The selected saved contact is no longer available.")
                        }
                        val export = QuickShareContactExport.from(profile, selection)
                        try {
                            selectionStore.replaceConfirmedKeys(confirmedKeys(selection))
                        } catch (error: Exception) {
                            throw QuickShareSelectionWriteFailure(error)
                        }
                        QuickShareContactHandoff(handoffId, export)
                    }
                }
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    destination = ProfileDestination.QuickShareReview,
                    quickShareSession = session.copy(pendingContactHandoff = handoff),
                )
            } catch (error: QuickShareSelectionWriteFailure) {
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    quickShareSession = session.copy(
                        selectionPersistenceError = error.cause?.message
                            ?: "Custom Share selection could not be remembered.",
                    ),
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    exporting = false,
                    quickShareSession = session.copy(
                        error = error.message ?: "The filtered contact could not be prepared.",
                        pendingContactHandoff = null,
                    ),
                )
            }
        }
    }

    fun takeQuickShareContactHandoff(): QuickShareContactHandoff? {
        val current = mutableState.value
        val session = current.quickShareSession ?: return null
        if (current.destination != ProfileDestination.QuickShareReview) return null
        val handoff = session.pendingContactHandoff ?: return null
        mutableState.value = current.copy(
            quickShareSession = session.copy(pendingContactHandoff = null),
        )
        return handoff
    }

    fun quickShareContactHandoffFailed(message: String) {
        val current = mutableState.value
        val session = current.quickShareSession ?: return
        if (current.destination != ProfileDestination.QuickShareReview) return
        mutableState.value = current.copy(
            quickShareSession = session.copy(error = message, pendingContactHandoff = null),
        )
    }

    private fun confirmedKeys(selection: Set<ProfileField>): Set<String> =
        QuickShareSelection.selectableFields
            .filter { it in selection }
            .mapTo(linkedSetOf()) { it.key }

    private class QuickShareSelectionWriteFailure(cause: Throwable) : Exception(cause.message, cause)


    fun requestDeleteProfile() {
        val current = mutableState.value
        if (current.busy || current.destination != ProfileDestination.Profile || current.saved == null) return
        mutableState.value = current.copy(confirmDeleteProfile = true, deleteError = null)
    }

    fun cancelDeleteProfile() {
        if (!mutableState.value.busy) {
            mutableState.value = mutableState.value.copy(confirmDeleteProfile = false)
        }
    }

    fun deleteProfile() {
        val current = mutableState.value
        val saved = current.saved ?: return
        if (current.busy || current.destination != ProfileDestination.Profile || !current.confirmDeleteProfile) return
        mutableState.value = current.copy(deletingProfile = true, confirmDeleteProfile = false, deleteError = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(saved.token) }
                val cleanupError = withContext(Dispatchers.IO) {
                    try {
                        repository.withFileOperation { cleanupContactFiles() }
                        null
                    } catch (error: ContactExportException) {
                        error.message
                    } catch (error: StoreException) {
                        error.message
                    }
                }
                val draft = ProfileDraft.empty()
                mutableState.value = ProfileScreenState(
                    loading = false,
                    destination = ProfileDestination.Welcome,
                    draft = draft,
                    initialDraft = draft,
                    firstRun = true,
                    contactCleanupError = cleanupError,
                    backupError = current.backupError,
                )
            } catch (error: StoreException) {
                val read = withContext(Dispatchers.IO) { repository.load() }
                mutableState.value = when (read) {
                    ProfileRead.Empty -> {
                        val draft = ProfileDraft.empty()
                        ProfileScreenState(loading = false, destination = ProfileDestination.Welcome,
                            draft = draft, initialDraft = draft, firstRun = true)
                    }
                    is ProfileRead.Ready -> current.copy(
                        deletingProfile = false, confirmDeleteProfile = false, deleteError = error.message,
                    )
                    is ProfileRead.Unavailable -> ProfileScreenState(
                        loading = false, destination = ProfileDestination.Profile, unavailable = read.message,
                        pendingOperation = read.pendingOperation, recoveryToken = read.recoveryToken,
                        deleteError = error.message,
                    )
                }
            }
        }
    }
}
