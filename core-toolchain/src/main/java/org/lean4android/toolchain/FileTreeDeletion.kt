package org.lean4android.toolchain

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes a tree without ever traversing symbolic links contained within it. */
internal fun File.deleteTreeWithoutFollowingLinks() {
    if (!Files.exists(toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: java.nio.file.Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}
