package com.eddyslarez.kmpsiprtc.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper

// Objeto singleton para gestionar el contexto de Android
@SuppressLint("StaticFieldLeak")
object AndroidContext {
    @SuppressLint("StaticFieldLeak")
    private var instance: Context? = null

    /**
     * Inicializa el contexto de Android.
     * Debe llamarse desde Application.onCreate() o antes de usar cualquier funcionalidad.
     */
    fun initialize(context: Context) {
        if (instance == null) {
            instance = context.applicationContext ?: context
        }
    }

    /**
     * Obtiene el contexto de Android. Lanza excepción si no está inicializado.
     */
    fun get(): Context {
        return instance ?: throw IllegalStateException(
            "Android context not initialized. Call AndroidContext.initialize() first."
        )
    }

    /**
     * Obtiene el contexto de Android de forma segura (nullable).
     */
    fun getOrNull(): Context? = instance

    /**
     * Obtiene la instancia de Application.
     */
    fun getApplication(): android.app.Application {
        var context = get()
        while (context is ContextWrapper) {
            if (context is android.app.Application) {
                return context
            }
            context = context.baseContext
        }
        throw IllegalStateException("Could not find Application context")
    }

    /**
     * Obtiene el nombre del paquete de la aplicación.
     */
    fun getPackageName(): String {
        return get().packageName
    }

    /**
     * Verifica si el contexto está inicializado.
     */
    fun isInitialized(): Boolean = instance != null

    /**
     * Limpia el contexto (útil para pruebas).
     */
    internal fun clear() {
        instance = null
    }
}

// ============ Funciones de compatibilidad (deprecated) ============
// Mantén estas solo si necesitas compatibilidad con código existente

@Deprecated(
    message = "Use AndroidContext.initialize() instead",
    replaceWith = ReplaceWith("AndroidContext.initialize(context)")
)
fun setAndroidContext(context: Context) {
    AndroidContext.initialize(context)
}

@Deprecated(
    message = "Use AndroidContext.get() instead",
    replaceWith = ReplaceWith("AndroidContext.get()")
)
fun getAndroidContext(): Context {
    return AndroidContext.get()
}

@Deprecated(
    message = "Use AndroidContext.getApplication() instead",
    replaceWith = ReplaceWith("AndroidContext.getApplication()")
)
fun getAndroidApplication(): android.app.Application {
    return AndroidContext.getApplication()
}

@Deprecated(
    message = "Use AndroidContext.getPackageName() instead",
    replaceWith = ReplaceWith("AndroidContext.getPackageName()")
)
fun getPackageName(): String {
    return AndroidContext.getPackageName()
}