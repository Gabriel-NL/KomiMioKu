package eu.kanade.tachiyomi.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Provider operations kept independent of database transactions so failures can be exercised on real files. */
internal class CollectionEditFiles(private val context: Context) {
    fun writeText(file: UniFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
    fun readText(file: UniFile) = file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    fun owned(file: UniFile, id: String) = file.findFile(".collection-edit-owner.txt")?.let { readText(it) == id } == true
    fun removeOwned(file: UniFile?, id: String) {
        if (file == null) return
        check(owned(file, id) || file.listFiles()?.isEmpty() == true) { "Recovery found an unrecognized folder; originals were preserved." }
        check(file.delete()) { "Cannot remove incomplete output" }
    }
    fun hash(file: UniFile): String {
        check(!file.isDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        file.openInputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun tree(file: UniFile, depth: Int = 0, seen: MutableSet<String> = mutableSetOf()): Map<String, String> {
        require(depth < 100 && seen.add(file.uri.toString())) { "Cyclic or deeply nested directory" }
        if (file.uri.scheme == "file") check(!Files.isSymbolicLink(File(checkNotNull(file.uri.path)).toPath())) { "Symbolic links are unsupported" }
        if (!file.isDirectory) return mapOf("" to hash(file))
        val result = linkedMapOf<String, String>("" to "directory")
        checkNotNull(file.listFiles()) { "Cannot list directory" }.forEach { child ->
            val name = checkNotNull(child.name)
            check(name != "." && name != ".." && '/' !in name && '\\' !in name)
            tree(child, depth + 1, seen).forEach { (path, value) -> result["$name/$path"] = value }
            require(result.size <= 250000) { "Too many collection files" }
        }
        return result
    }
    fun copy(input: UniFile, parent: UniFile, name: String): UniFile {
        check(parent.listFiles().orEmpty().none { it.name.equals(name, true) }) { "Duplicate file name: $name" }
        val target = if (input.isDirectory) parent.createDirectory(name) else parent.createFile(name)
        checkNotNull(target) { "Cannot create $name" }
        check(target.name == name) { "Storage provider changed the requested file name: $name" }
        if (input.isDirectory) {
            checkNotNull(input.listFiles()).forEach { copy(it, target, checkNotNull(it.name)) }
        } else {
            val oldHash = hash(input)
            input.openInputStream().use { stream -> context.contentResolver.openOutputStream(target.uri, "wt")!!.use { stream.copyTo(it) } }
            check(hash(target) == oldHash && hash(input) == oldHash) { "File changed or copy verification failed: $name" }
        }
        return target
    }
    fun checkedRename(file: UniFile, parent: UniFile, name: String) {
        check(parent.findFile(name) == null) { "Name already exists: $name" }
        check(file.renameTo(name) && parent.findFile(name) != null) { "Storage provider cannot safely rename this folder" }
    }

    fun rollbackOutput(file: UniFile, id: String, signature: Map<String, String>) {
        check(owned(file, id) && tree(file) == signature) { "Edited files changed after interruption; both versions were preserved for recovery." }
        removeOwned(file, id)
    }
}
