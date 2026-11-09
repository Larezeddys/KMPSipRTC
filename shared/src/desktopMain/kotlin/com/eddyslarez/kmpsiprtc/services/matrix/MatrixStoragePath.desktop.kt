package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.DesktopDataDirs

actual fun matrixStoragePath(): String = DesktopDataDirs.matrixDir().absolutePath
