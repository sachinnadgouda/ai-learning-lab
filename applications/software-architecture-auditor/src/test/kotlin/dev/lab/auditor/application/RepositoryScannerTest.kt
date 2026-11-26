package dev.lab.auditor.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryScannerTest {

    private val createdDirs = mutableListOf<Path>()

    private fun workspaceTempDir(prefix: String): Path {
        val base = Path.of("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        val dir = base.resolve("$prefix-${UUID.randomUUID().toString().take(8)}")
        Files.createDirectories(dir)
        createdDirs.add(dir)
        return dir
    }

    @AfterEach
    fun cleanup() {
        createdDirs.forEach { dir ->
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        createdDirs.clear()
    }

    @Test
    fun `rejects path outside allowed scan roots`() {
        val allowed = workspaceTempDir("allowed")
        val outside = workspaceTempDir("outside")
        val scanner = RepositoryScanner(InMemoryFileCache())

        assertThrows<PathTraversalException> {
            scanner.validateAllowedRoot(outside, listOf(allowed))
        }
        assertThrows<PathTraversalException> {
            scanner.validateAllowedRoot(allowed.resolve("../${outside.fileName}"), listOf(allowed))
        }

        val ok = scanner.validateAllowedRoot(allowed, listOf(allowed))
        assertEquals(allowed.toRealPath(), ok)

        // Symlink inside allowed root pointing outside allowed root must be rejected
        val symlinkEscape = allowed.resolve("escape-link")
        Files.createSymbolicLink(symlinkEscape, outside)
        assertThrows<PathTraversalException> {
            scanner.validateAllowedRoot(symlinkEscape, listOf(allowed))
        }

        // Non-existent traversal path escaping allowed root must also throw PathTraversalException
        assertThrows<PathTraversalException> {
            scanner.validateAllowedRoot(allowed.resolve("../../definitely-outside-nonexistent"), listOf(allowed))
        }
    }

    @Test
    fun `blocks symlink file, symlink directory, dangling symlink, and chained symlink escaping scan root`() {
        val repo = workspaceTempDir("repo-symlink")
        val externalSecretDir = workspaceTempDir("external-secret")
        val externalFile = externalSecretDir.resolve("Secret.kt")
        Files.writeString(externalFile, "package secret\nimport com.evil.Leak\n")

        val domainDir = repo.resolve("domain")
        Files.createDirectories(domainDir)
        Files.writeString(domainDir.resolve("Safe.kt"), "package com.safe.domain\nclass Safe\n")
        Files.createSymbolicLink(domainDir.resolve("Escaped.kt"), externalFile)
        Files.createSymbolicLink(repo.resolve("escaped-dir"), externalSecretDir)
        Files.createSymbolicLink(domainDir.resolve("Dangling.kt"), repo.resolve("does-not-exist.kt"))
        // Chained symlink: repo/domain/Chain.kt -> repo/domain/Escaped.kt -> externalFile
        Files.createSymbolicLink(domainDir.resolve("Chain.kt"), domainDir.resolve("Escaped.kt"))

        val scanner = RepositoryScanner(InMemoryFileCache())
        // Default safe mode skips all escaping/dangling symlinks and records security warnings
        val output = scanner.scan(repo, strictSymlinkRejection = false)
        assertEquals(1, output.graph.nodes.size)
        assertEquals("domain/Safe.kt", output.graph.nodes.single().path)
        assertTrue(output.securityWarnings.size >= 4)

        // Strict mode throws PathTraversalException
        assertThrows<PathTraversalException> {
            scanner.scan(repo, strictSymlinkRejection = true)
        }
    }

    @Test
    fun `enforces maxFiles and maxFileBytes bounds`() {
        val repo = workspaceTempDir("repo-limits")
        val modDir = repo.resolve("mod")
        Files.createDirectories(modDir)
        Files.writeString(modDir.resolve("Big.kt"), "package mod\n" + "a".repeat(2000))
        Files.writeString(modDir.resolve("A.kt"), "package mod\nclass A\n")
        Files.writeString(modDir.resolve("B.kt"), "package mod\nclass B\n")
        Files.writeString(modDir.resolve("C.kt"), "package mod\nclass C\n")

        val scanner = RepositoryScanner(
            fileCache = InMemoryFileCache(),
            limits = ScannerLimits(maxFiles = 2, maxFileBytes = 500),
        )
        val out = scanner.scan(repo)
        assertEquals(2, out.graph.nodes.size)
        assertTrue(out.telemetry.truncatedByLimit)
        assertTrue(out.telemetry.filesSkipped >= 2)
    }
}
