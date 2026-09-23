package app.framealt

import android.app.Application
import android.content.pm.ApplicationInfo
import app.framealt.data.FrameStore
import app.framealt.data.IdentityStore
import app.framealt.data.Settings
import app.framealt.device.FrameConnectionManager
import app.framealt.device.NsdDiscovery
import app.framealt.device.WifiSocketFactory
import app.framealt.diag.EventLog

class FrameAltApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Hand-rolled dependency container.
 *
 * The graph is about ten objects with no cycles and no scoping beyond "one per process", so
 * a DI framework would cost build time and indirection for nothing. Revisit if this grows
 * past roughly twenty entries.
 */
class AppContainer(application: Application) {

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
}
