package com.coach.migrate

import java.nio.file.Path
import kotlin.io.path.*

private const val USAGE = """Usage: migrate-deeptutor --dry-run [--capture-only | --snapshot DIR]
    [--source DIR] [--staging DIR] [--out DIR] [--weights FILE]
       migrate-deeptutor seed-content --snapshot DIR [--data DIR] [--corpus DIR]
Defaults: source ~/DeepTutor, staging ~/Coach/migrate-staging, out ~/Coach/migrate-out.
seed-content defaults: data ~/Coach/data, corpus ~/Coach/corpus/ECNM10119-健康经济学/教材.
No learning history is imported. See README for independent verification.
"""
fun resolvedDestination(path: Path): Path {
    val absolute=path.toAbsolutePath().normalize()
    return if(absolute.exists()) absolute.toRealPath() else resolvedDestination(absolute.parent).resolve(absolute.fileName)
}
fun requirePrivateDestination(path: Path, sources: List<Path>) {
    require(sources.none { path.startsWith(it) }) { "Staging/output must be outside DeepTutor" }
    var parent:Path?=path
    while(parent!=null) {
        require(!parent.resolve(".git").exists()) { "Real data must never be written inside a repository" }
        parent=parent.parent
    }
}
fun main(args: Array<String>) {
    if(args.contentEquals(arrayOf("--help"))) { println(USAGE);return }
    if(args.firstOrNull()=="seed-content") {
        val options=mutableMapOf<String,String>();var i=1
        while(i<args.size) {
            val key=args[i++]
            require(key in setOf("--snapshot","--data","--corpus")) { "Unknown seed-content option: $key\n$USAGE" }
            require(key !in options) { "Duplicate option: $key" }
            options[key]=args.getOrElse(i++) { error("Missing value for $key\n$USAGE") }
        }
        val home=Path.of(System.getProperty("user.home"))
        val snap=Path.of(options["--snapshot"] ?: error("seed-content requires --snapshot DIR\n$USAGE")).toRealPath()
        val data=resolvedDestination(options["--data"]?.let(Path::of) ?: home.resolve("Coach/data"))
        val corpus=resolvedDestination(options["--corpus"]?.let(Path::of) ?: home.resolve("Coach/corpus"))
        val protectedSources=listOf(resolvedDestination(home.resolve("DeepTutor")))
        requirePrivateDestination(data,protectedSources);requirePrivateDestination(corpus,protectedSources)
        require(!snap.startsWith(resolvedDestination(home.resolve("DeepTutor")))) { "Snapshot must be a read-only staging copy, never DeepTutor" }
        val prepared=prepareSeed(snap)
        val written=seedContent(prepared,data,corpus)
        println("Seeded course=${prepared.courseId} modules=${prepared.modules.size} knowledge_points=${prepared.knowledgePoints.size} anchors=${prepared.knowledgePoints.size} exam_weight_total=${prepared.weights.values.sum()} events_added=${written.eventsAdded}")
        println("manifest_sha256=${prepared.sha256} manifest_bytes=${prepared.manifestBytes.size} idempotent=${written.idempotent}")
        println("Textbook pages are stored in local Coach data/blobs and corpus; no learning records were imported.")
        return
    }
    val options = mutableMapOf<String, String>()
    var i=0
    while(i<args.size) {
        val key=args[i++]
        require(key in setOf("--dry-run", "--capture-only", "--source", "--staging", "--snapshot", "--out", "--weights")) { "Unknown option: $key\n$USAGE" }
        require(key !in options) { "Duplicate option: $key" }
        options[key] = if(key in setOf("--dry-run", "--capture-only")) "true" else args.getOrElse(i++) { error("Missing value for $key") }
    }
    require(options.containsKey("--dry-run")) { "Only --dry-run is supported; no formal import is permitted\n$USAGE" }
    require(!(options.containsKey("--capture-only") && options.containsKey("--snapshot"))) { "Choose capture-only or an existing snapshot" }
    val home=Path.of(System.getProperty("user.home"))
    fun option(key:String, default:Path)= resolvedDestination(options[key]?.let(Path::of) ?: default)
    val source=option("--source",home.resolve("DeepTutor"))
    val protectedSources=listOf(source,resolvedDestination(home.resolve("DeepTutor")))
    val staging=option("--staging",home.resolve("Coach/migrate-staging"))
    val out=option("--out",home.resolve("Coach/migrate-out"))
    requirePrivateDestination(staging,protectedSources)
    requirePrivateDestination(out,protectedSources)
    val snap = options["--snapshot"]?.let { Path.of(it).toRealPath() } ?: snapshot(source, staging,
        option("--weights",home.resolve("dev/coach/docs/background/期中分值覆盖表.md")))
    requirePrivateDestination(snap,protectedSources)
    if(options.containsKey("--capture-only")) { println("Captured: $snap"); return }
    val result=mapSnapshot(snap)
    privateDir(out)
    out.resolve("mapping.json").writeText(canonical(result.mapping))
    out.resolve("report.md").writeText(result.report)
    println(canonical(result.mapping.getValue("counts")))
    println("Dry-run outputs written outside the source and repository")
}
