package com.kiwixrag.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathResolverTest {
    @Test fun primaryVolume() = assertEquals("/storage/emulated/0/Download/gemma.task", PathResolver.documentIdToPath("primary:Download/gemma.task"))
    @Test fun primaryRoot() = assertEquals("/storage/emulated/0", PathResolver.documentIdToPath("primary:"))
    @Test fun sdCard() = assertEquals("/storage/1A2B-3C4D/Kiwix/wiki.zim", PathResolver.documentIdToPath("1A2B-3C4D:Kiwix/wiki.zim"))
    @Test fun trailingSlashTrimmed() = assertEquals("/storage/emulated/0/Kiwix", PathResolver.documentIdToPath("primary:Kiwix/"))
    @Test fun downloadsRaw() = assertEquals("/storage/emulated/0/Download/m.litertlm", PathResolver.documentIdToPath("raw:/storage/emulated/0/Download/m.litertlm"))
    @Test fun homeVolume() = assertEquals("/storage/emulated/0/Documents/x.zim", PathResolver.documentIdToPath("home:x.zim"))
    @Test fun numericIdCannotBeMapped() = assertNull(PathResolver.documentIdToPath("1234"))
    @Test fun blankVolume() = assertNull(PathResolver.documentIdToPath(":foo"))
}
