package app.framealt

import android.app.Application
import android.content.pm.ApplicationInfo
import app.framealt.data.FrameStore
import app.framealt.data.IdentityStore
import app.framealt.data.SendDatabase
import app.framealt.data.Settings
import app.framealt.device.FrameConnectionManager
import app.framealt.device.NsdDiscovery
import app.framealt.device.WifiSocketFactory
import app.framealt.diag.EventLog
import app.framealt.media.ImagePipeline
import app.framealt.send.SendNotifications
import app.framealt.send.SendQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class FrameAltApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/**
 * Hand-rolled dependency container.
 *
 * The graph is about fifteen objects with no cycles and no scoping beyond "one per process",
 * so a DI framework would cost build time and indirection for nothing. Revisit if this grows
 * past roughly twenty entries.
 */
class AppContainer(application: Application) {

    /** Work that outlives any screen: queue housekeeping and resuming on Wi-Fi. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val eventLog = EventLog(
        mirrorToLogcat = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    val identityStore = IdentityStore(application)

    val frameStore = FrameStore(application)

    val settings = Settings(application)

    val socketFactory = WifiSocketFactory(application, eventLog).also { it.start() }

    val discovery = NsdDiscovery(application, eventLog)

    val connections = FrameConnectionManager(
        identityStore = identityStore,
        frameStore = frameStore,
        settings = settings,
        discovery = discovery,
        socketFactory = socketFactory,
        eventLog = eventLog,
    )

    val database = SendDatabase.open(application)

    val imagePipeline = ImagePipeline(application)

    val sendNotifications = SendNotifications(application)

    val sendQueue = SendQueue(
        context = application,
        queue = database.queue(),
        outbox = { imagePipeline.outbox },
        eventLog = eventLog,
    )

    /**
     * Photos picked on Home, handed to the review screen. A field rather than a navigation
     * argument because a batch can be a hundred URIs long.
     */
    var pendingPicks: List<android.net.Uri> = emptyList()

    fun start() {
        sendNotifications.createChannel()
        scope.launch { sendQueue.tidy() }
        // Wi-Fi coming back is the moment a waiting queue should try again, not whenever
        // the backoff timer happens to expire. The first value is the state at startup.
        scope.launch {
            socketFactory.wifiAvailable.drop(1).filter { it }.collect {
                if (database.queue().countUnfinished() > 0) sendQueue.kick()
            }
        }
    }
}
