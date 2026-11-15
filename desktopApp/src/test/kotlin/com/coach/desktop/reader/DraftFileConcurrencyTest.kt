package com.coach.desktop.reader

import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Holds the real read channel, rather than substituting a failed put or a synthetic exception. */
class DraftFileConcurrencyTest {
    private val scope = "local-library:concurrency-fixture"
    private val identity = DraftIdentity(scope, "synthetic", "v1", 1, 0, 5, "synthetic-page-sha")
    private val original = StoredDraft(identity, "note-1", "quote", "before", 7)
    private val updated = original.copy(note = "after")

    @Test fun getAndReplacementAcrossInstancesKeepTheLatestDraft() = overlap(list = false)
    @Test fun listAndReplacementAcrossInstancesKeepTheLatestDraft() = overlap(list = true)

    private fun overlap(list: Boolean) {
        val root = Files.createTempDirectory("draft-read-replace-").toRealPath()
        val gate = ReadGate(root)
        val writer = FileNoteDraftStore(gate.path, scope)
        assertTrue(writer.put(original))
        val reader = FileNoteDraftStore(gate.path, scope) // A separate session/store instance.
        val pool = Executors.newFixedThreadPool(2)
        try {
            gate.armed.set(true)
            val reading = pool.submit<StoredDraft?> {
                if (list) reader.listFor(identity.doc, identity.version, identity.pdfPage).single() else reader.get(identity)
            }
            assertTrue(gate.opened.await(10, TimeUnit.SECONDS), "production read must hold the actual channel")
            val started = CountDownLatch(1)
            val writing = pool.submit<Boolean> { started.countDown(); writer.put(updated) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val completedWhileOpen = try { writing.get(500, TimeUnit.MILLISECONDS); true } catch (_: TimeoutException) { false }
            gate.release.countDown()
            assertEquals(original, reading.get(10, TimeUnit.SECONDS))
            val persisted = writing.get(10, TimeUnit.SECONDS)
            println("RESULT list=$list completedWhileReadOpen=$completedWhileOpen put=$persisted")
            assertTrue(persisted, "replacement must not lose the newest unsaved text to our own read handle")
            assertFalse(completedWhileOpen, "replacement must wait until the store's read channel closes")
            assertEquals(updated, FileNoteDraftStore(root, scope).get(identity))
        } finally {
            gate.release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun externalReadHandleStillRefusesWithoutDamagingTheRecoverableDraft() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("draft-external-read-").toRealPath()
        val store = FileNoteDraftStore(root, scope)
        assertTrue(store.put(original))
        val target = Files.list(root).use { it.filter { p -> p.toString().endsWith(".json") }.findFirst().orElseThrow() }
        FileChannel.open(target, StandardOpenOption.READ).use {
            assertFalse(store.put(updated), "an uncoordinated external handle must still report failure")
            assertEquals(original, FileNoteDraftStore(root, scope).get(identity))
        }
        assertTrue(store.put(updated))
        assertEquals(updated, FileNoteDraftStore(root, scope).get(identity))
    }
}

/** A forwarding provider changes only read timing; all opens and moves use the platform provider. */
private class ReadGate(root: Path) {
    val armed = AtomicBoolean(false)
    val opened = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val native = root.fileSystem.provider()
    private fun real(path: Path) = (path as? GatePath)?.delegate ?: path
    private val provider: FileSystemProvider = object : FileSystemProvider() {
        override fun getScheme() = "draft-read-gate"
        override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = throw UnsupportedOperationException()
        override fun getFileSystem(uri: URI): FileSystem = fs
        override fun getPath(uri: URI): Path = wrap(Path.of(uri))
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
            val channel = native.newByteChannel(real(path), options, *attrs)
            if (StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options || !armed.compareAndSet(true, false)) return channel
            val handle = runCatching {
                val fd = channel.javaClass.getDeclaredField("fd").apply { isAccessible = true }.get(channel)
                fd.javaClass.getDeclaredField("handle").apply { isAccessible = true }.getLong(fd)
            }.getOrNull()
            println("READ_OPEN pid=${ProcessHandle.current().pid()} thread=${Thread.currentThread().id} path=${real(path)} options=$options channel=${channel.javaClass.name} handle=$handle")
            opened.countDown()
            return object : SeekableByteChannel by channel {
                override fun read(dst: ByteBuffer): Int {
                    check(release.await(10, TimeUnit.SECONDS)) { "read gate not released" }
                    return channel.read(dst)
                }
                override fun close() {
                    channel.close()
                    println("READ_CLOSED thread=${Thread.currentThread().id} path=${real(path)}")
                }
            }
        }
        override fun move(source: Path, target: Path, vararg options: CopyOption) {
            try { native.move(real(source), real(target), *options) }
            catch (failure: FileSystemException) {
                println("MOVE_REFUSED pid=${ProcessHandle.current().pid()} thread=${Thread.currentThread().id} type=${failure.javaClass.name} source=${failure.file} target=${failure.otherFile} reason=${failure.reason} readerStillOpen=${release.count > 0}")
                throw failure
            }
        }
        override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
            val stream = native.newDirectoryStream(real(dir)) { filter.accept(wrap(it)) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = stream.map { wrap(it) }.toMutableList().iterator()
                override fun close() = stream.close()
            }
        }
        override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = native.createDirectory(real(dir), *attrs)
        override fun delete(path: Path) = native.delete(real(path))
        override fun copy(source: Path, target: Path, vararg options: CopyOption) = native.copy(real(source), real(target), *options)
        override fun isSameFile(path: Path, path2: Path) = native.isSameFile(real(path), real(path2))
        override fun isHidden(path: Path) = native.isHidden(real(path))
        override fun getFileStore(path: Path): FileStore = native.getFileStore(real(path))
        override fun checkAccess(path: Path, vararg modes: AccessMode) = native.checkAccess(real(path), *modes)
        override fun <V : FileAttributeView?> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? = native.getFileAttributeView(real(path), type, *options)
        override fun <A : BasicFileAttributes?> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A = native.readAttributes(real(path), type, *options)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> = native.readAttributes(real(path), attributes, *options)
        override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) = native.setAttribute(real(path), attribute, value, *options)
    }
    private val fs: FileSystem = object : FileSystem() {
        override fun provider(): FileSystemProvider = provider
        override fun close() = Unit
        override fun isOpen() = true
        override fun isReadOnly() = false
        override fun getSeparator() = root.fileSystem.separator
        override fun getRootDirectories(): MutableIterable<Path> = root.fileSystem.rootDirectories.map { wrap(it) }.toMutableList()
        override fun getFileStores(): MutableIterable<FileStore> = root.fileSystem.fileStores.toMutableList()
        override fun supportedFileAttributeViews(): MutableSet<String> = root.fileSystem.supportedFileAttributeViews()
        override fun getPath(first: String, vararg more: String) = wrap(root.fileSystem.getPath(first, *more))
        override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = root.fileSystem.getPathMatcher(syntaxAndPattern)
        override fun getUserPrincipalLookupService(): UserPrincipalLookupService = root.fileSystem.userPrincipalLookupService
        override fun newWatchService(): WatchService = throw UnsupportedOperationException()
    }
    private fun wrap(path: Path): Path = GatePath(path)
    val path: Path = wrap(root)
    private inner class GatePath(val delegate: Path) : Path by delegate {
        override fun getFileSystem(): FileSystem = fs
        override fun resolve(other: String) = wrap(delegate.resolve(other))
        override fun resolve(other: Path) = wrap(delegate.resolve(real(other)))
        override fun getParent(): Path? = delegate.parent?.let { wrap(it) }
        override fun toAbsolutePath() = wrap(delegate.toAbsolutePath())
        override fun normalize() = wrap(delegate.normalize())
        override fun toString() = delegate.toString()
    }
}
