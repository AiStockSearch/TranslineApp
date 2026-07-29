// Фрагмент для вставки в MainApplication.kt хост-приложения
import org.transline.geoworker.GeoWorkerPackage

// внутри getPackages():
PackageList(this).packages.apply {
    add(GeoWorkerPackage())
}
