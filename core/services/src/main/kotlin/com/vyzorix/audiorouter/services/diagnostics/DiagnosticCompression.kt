package com.vyzorix.audiorouter.services.diagnostics

import com.vyzorix.audiorouter.services.storage.logs.CrashSnapshotExporter
import java.io.File

public class DiagnosticCompression(private val exporter: CrashSnapshotExporter = CrashSnapshotExporter()) { public fun compress(sourceDir: File, outputDir: File): File = exporter.export(sourceDir, outputDir) }
