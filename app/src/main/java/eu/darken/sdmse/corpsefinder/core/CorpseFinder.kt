package eu.darken.sdmse.corpsefinder.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.R
import eu.darken.sdmse.common.ca.CaString
import eu.darken.sdmse.common.coroutine.AppScope
import eu.darken.sdmse.common.debug.logging.Logging.Priority.*
import eu.darken.sdmse.common.debug.logging.asLog
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.easterEggProgressMsg
import eu.darken.sdmse.common.files.core.GatewaySwitch
import eu.darken.sdmse.common.files.core.deleteAll
import eu.darken.sdmse.common.forensics.FileForensics
import eu.darken.sdmse.common.pkgs.pkgops.PkgOps
import eu.darken.sdmse.common.progress.*
import eu.darken.sdmse.common.sharedresource.SharedResource
import eu.darken.sdmse.common.sharedresource.keepResourceHoldersAlive
import eu.darken.sdmse.corpsefinder.core.filter.CorpseFilter
import eu.darken.sdmse.corpsefinder.core.tasks.CorpseFinderDeleteTask
import eu.darken.sdmse.corpsefinder.core.tasks.CorpseFinderScanTask
import eu.darken.sdmse.corpsefinder.core.tasks.CorpseFinderTask
import eu.darken.sdmse.main.core.SDMTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CorpseFinder @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val filterFactories: Set<@JvmSuppressWildcards CorpseFilter.Factory>,
    fileForensics: FileForensics,
    private val gatewaySwitch: GatewaySwitch,
    pkgOps: PkgOps,
) : SDMTool, Progress.Client {

    private val usedResources = setOf(fileForensics, gatewaySwitch, pkgOps)

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope)

    private val progressPub = MutableStateFlow<Progress.Data?>(null)
    override val progress: Flow<Progress.Data?> = progressPub
    override fun updateProgress(update: (Progress.Data?) -> Progress.Data?) {
        progressPub.value = update(progressPub.value)
    }

    private val internalData = MutableStateFlow(null as Data?)
    val data: Flow<Data?> = internalData

    override val type: SDMTool.Type = SDMTool.Type.CORPSEFINDER

    private val jobLock = Mutex()
    override suspend fun submit(task: SDMTool.Task): SDMTool.Task.Result = jobLock.withLock {
        task as CorpseFinderTask
        log(TAG, INFO) { "submit($task) starting..." }
        updateProgressPrimary(R.string.general_progress_loading)
        updateProgressSecondary(easterEggProgressMsg)
        updateProgressCount(Progress.Count.Indeterminate())

        try {
            val result = keepResourceHoldersAlive(usedResources) {
                when (task) {
                    is CorpseFinderScanTask -> performScan(task)
                    is CorpseFinderDeleteTask -> deleteCorpses(task)
                }
            }
            log(TAG, INFO) { "submit($task) finished: $result" }
            result
        } finally {
            updateProgress { null }
        }
    }

    private suspend fun performScan(task: CorpseFinderScanTask): CorpseFinderTask.Result = try {
        log(TAG) { "performScan($task)" }

        val scanStart = System.currentTimeMillis()
        internalData.value = null

        val filters = filterFactories
            .filter { it.isEnabled() }
            .map { it.create() }
            .onEach { log(TAG) { "Created filter: $it" } }

        val results = filters
            .map { filter ->
                filter
                    .withProgress(this@CorpseFinder) { scan() }
                    .also { log(TAG) { "$filter found ${it.size} corpses" } }
            }
            .flatten()

        results.forEach { log(TAG, INFO) { "Result: $it" } }

        internalData.value = Data(
            corpses = results
        )

        val scanStop = System.currentTimeMillis()
        val time = Duration.ofMillis(scanStop - scanStart)
        CorpseFinderScanTask.Success(
            duration = time
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        log(TAG, ERROR) { "performScan($task) failed: ${e.asLog()}" }
        CorpseFinderScanTask.Error(e)
    }

    private suspend fun deleteCorpses(task: CorpseFinderDeleteTask): CorpseFinderTask.Result = try {
        log(TAG) { "deleteCorpses(): $task" }

        val deleted = mutableSetOf<Corpse>()
        val snapshot = internalData.value ?: throw IllegalStateException("Data is null")
        updateProgressPrimary(R.string.general_progress_deleting)

        task.toDelete.forEach { target ->
            val corpse = snapshot.corpses.single { it.path == target }
            updateProgressSecondary(corpse.path.userReadableName)

            log(TAG) { "Deleting $target..." }
            corpse.path.deleteAll(gatewaySwitch)
            log(TAG) { "Deleted $target!" }

            deleted.add(corpse)
        }

        updateProgressPrimary(R.string.general_progress_loading)
        updateProgressSecondary(CaString.EMPTY)

        internalData.value = snapshot.copy(
            corpses = snapshot.corpses.minus(deleted)
        )

        CorpseFinderDeleteTask.Success(
            reclaimedSize = deleted.sumOf { it.size }
        )
    } catch (e: Exception) {
        log(TAG, ERROR) { "deleteCorpses($task) failed:\n${e.asLog()}" }
        CorpseFinderDeleteTask.Error(e)
    }

    data class Data(
        val corpses: Collection<Corpse>
    ) {
        val totalSize: Long by lazy { corpses.sumOf { it.size } }
    }

    @InstallIn(SingletonComponent::class)
    @Module
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: CorpseFinder): SDMTool
    }

    companion object {
        private val TAG = logTag("CorpseFinder")
    }
}