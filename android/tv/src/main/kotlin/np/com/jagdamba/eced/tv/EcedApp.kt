package np.com.jagdamba.eced.tv

import android.app.Application
import np.com.jagdamba.eced.core.data.CatalogRepository
import np.com.jagdamba.eced.core.data.DeviceRepository
import np.com.jagdamba.eced.core.data.ProgressRepository

/**
 * No DI framework on purpose. Three repositories and a two-day deadline do not
 * justify Hilt's annotation-processing build cost.
 */
class EcedApp : Application() {

    val catalog: CatalogRepository by lazy { CatalogRepository() }
    val progress: ProgressRepository by lazy { ProgressRepository() }
    val devices: DeviceRepository by lazy { DeviceRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: EcedApp
            private set
    }
}
