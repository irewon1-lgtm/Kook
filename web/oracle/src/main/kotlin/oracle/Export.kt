package oracle

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.ranking.DynamicRankingEngine
import com.krstock.v3.data.ranking.RankMetric
import com.krstock.v3.data.analysis.IntegratedInterpretationEngine
import com.krstock.v3.data.analysis.QuarterlyTrendAnalyzer
import com.krstock.v3.data.history.QuarterlyHistoryRepository
import com.krstock.v3.data.evidence.ContextEvidenceRepository
import com.krstock.v3.data.stage.FinancialSafetySnapshotParser
import com.krstock.v3.data.stage.FinancialSafetyRuntimeStore
import com.krstock.v3.data.stage.ValuationBandPolicy
import com.krstock.v3.data.candidate.FinalCandidateSnapshotUpdater
import com.krstock.v3.data.candidate.FinalCandidateRuntimeStore
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun tree(value: Any?): JsonElement = gson.toJsonTree(value)
private fun failure(error: Throwable) = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
private fun write(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(gson.toJson(value) + "\n") }

private fun writeContext(file: File, observation: JsonObject) {
    val temporary=File(file.parentFile,".${file.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { out -> out.write((gson.toJson(observation)+"\n").toByteArray(Charsets.UTF_8)); out.fd.sync() }
        try { Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
private fun historyTree(h: QuarterlyHistory): JsonObject = tree(h).asJsonObject.apply {
    add("latestTargetPoint", tree(h.latestTargetPoint)); addProperty("comparisonScope",h.comparisonScope)
    add("comparablePoints",tree(h.comparablePoints)); addProperty("availableQuarterCount",h.availableQuarterCount)
    addProperty("directQuarterCount",h.directQuarterCount); addProperty("reconstructedQuarterCount",h.reconstructedQuarterCount)
    addProperty("hasFourQuarters",h.hasFourQuarters); addProperty("hasEightQuarters",h.hasEightQuarters); addProperty("hasTrailingGap",h.hasTrailingGap)
}
private fun financialTree(record: FinancialSafetyRecord): JsonObject = tree(record).asJsonObject.apply {
    record.facts?.let { facts -> getAsJsonObject("facts").add("amountsExact", tree(facts.amounts.mapValues { (_,value) -> value?.toString() })) }
}
private fun evidenceTree(e: EvidenceBundle): JsonObject = tree(e).asJsonObject.apply {
    add("all",tree(e.all)); addProperty("primarySourceCount",e.primarySourceCount)
}

fun main(args: Array<String>) {
    val opts = linkedMapOf<String,String>()
    var index=0
    while (index < args.size) {
        val key=args[index++]; require(key.startsWith("--")) { "Invalid argument $key" }
        opts[key]=if (key in setOf("--collect-context","--context-refresh")) "true" else args.getOrNull(index++) ?: error("Missing value $key")
    }
    val root=File(opts.getValue("--root")); val output=File(opts.getValue("--output"))
    require(!output.exists() || output.listFiles().orEmpty().isEmpty()) { "Output must be absent or empty (publish a new generation): $output" }
    val inputs=linkedMapOf<String,File>()
    for (key in listOf("quant","financial","candidates","history")) inputs[key]=File(opts.getValue("--$key"))
    val inputHashes=linkedMapOf<String,String>()
    for ((name,file) in inputs) if (file.isFile) inputHashes[name]=hash(file.readBytes())
    // Hash the exact original sources compiled, including the old bundled rows used only
    // as the original parser's identity/coverage reference. Active rows come from parseAndValidate.
    val original=File(root,"app/src/main/java/com/krstock/v3/data")
    val compiledDirs=setOf("model","generated","repository","peer","ranking","analysis","stage","candidate","history","evidence")
    original.walkTopDown().filter { it.isFile && it.extension=="kt" && (it.relativeTo(original).invariantSeparatorsPath.substringBefore('/') in compiledDirs || it.name=="SnapshotAutoUpdater.kt") }
        .sortedBy { it.path }.forEach { inputHashes[it.relativeTo(root).invariantSeparatorsPath]=hash(it.readBytes()) }
    val parsed=SnapshotAutoUpdater.parseAndValidate(inputs.getValue("quant").readText())
    GeneratedRealQuantSnapshot.installRuntimeSnapshot(parsed.snapshotDate,parsed.priceCutoffDate,parsed.dartFileName,parsed.rows,parsed.m01Available,parsed.m02Available,parsed.m03Available,parsed.m04Available,parsed.completeCount)
    FinancialSafetyRuntimeStore.clear(); FinalCandidateRuntimeStore.clear()
    val stocks=StockRepository.getAllStocks(); val codes=stocks.map {it.issuerId}.toSet()
    val financialResult=runCatching {
        val raw=JSONObject(inputs.getValue("financial").readText())
        val safety=raw.optJSONObject("financial_safety") ?: raw
        require(safety.optString("quant_records_sha256")==opts.getValue("--quant-records-sha256")) { "financial-safety quant records fingerprint mismatch" }
        FinancialSafetySnapshotParser.parse(safety,parsed.snapshotDate,codes).also { FinancialSafetyRuntimeStore.install(it) }
    }
    val candidateResult=runCatching {
        val records=FinalCandidateSnapshotUpdater.parseAndValidate(inputs.getValue("candidates").readText(),parsed.snapshotDate)
        FinalCandidateRuntimeStore.install(parsed.snapshotDate,records)
        FinalCandidateRuntimeStore.current(parsed.snapshotDate)
    }
    val candidates=FinalCandidateRepository.getFinalCandidateMap()
    val valuations=stocks.associate { it.issuerId to ValuationBandPolicy.classify(it.m03Per) }
    val historyResult=runCatching {
        JSONObject(inputs.getValue("history").readText()).also {
            require(it.getInt("schema_version")==1); require(it.getJSONObject("records").length() >= 2000)
            require(it.getJSONArray("target_periods").length() in 4..8)
        }
    }
    val cache=opts["--context-cache"]?.let(::File)
    val concurrency=opts["--context-concurrency"]?.toInt() ?: 4
    val limit=opts["--context-limit"]?.toInt() ?: 10
    require(concurrency in 1..16); require(limit in 0..2649)
    val selected=opts["--context-codes"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    require(selected.all {it in codes}) { "Unknown context selection ${selected-codes}" }
    val collect=opts["--collect-context"]=="true"
    val collected=linkedMapOf<String,JsonObject>()
    if (collect) {
        require(cache != null) { "--collect-context requires --context-cache for observation provenance" }; cache.mkdirs()
        val targets=stocks.filter { selected.isEmpty() || it.issuerId in selected }.take(limit).filter { stock ->
            opts["--context-refresh"]=="true" || !runCatching {
                val old=gson.fromJson(File(cache,"${stock.issuerId}.json").readText(),JsonObject::class.java)
                old["schemaVersion"].asInt==1 && old["issuerId"].asString==stock.issuerId && old["evidence"].asJsonObject["loaded"].asBoolean && old["collectedAt"].asString.isNotBlank()
            }.getOrDefault(false)
        }
        println("Context collection: ${targets.size} pending, concurrency=$concurrency, checkpoint=$cache")
        val executor=Executors.newFixedThreadPool(concurrency)
        try {
            val jobs=targets.map { stock -> executor.submit(Callable {
                val file=File(cache,"${stock.issuerId}.json")
                val previous=runCatching { ContextCachePolicy.validate(gson.fromJson(file.readText(),JsonObject::class.java),stock.issuerId) }.getOrNull()
                val checked=Instant.now().toString()
                val evidence=runCatching { runBlocking { ContextEvidenceRepository.load(stock.issuerId) } }.getOrElse { EvidenceBundle(stock.issuerId,loaded=true,error=failure(it)) }
                val observation=ContextCachePolicy.refresh(previous,evidence,checked)
                writeContext(file,observation)
                stock.issuerId to observation
            }) }
            jobs.forEach { val (code,result)=it.get(); collected[code]=result; println("context $code ${ContextCachePolicy.evidence(result).error ?: "collected; category availability separate"}") }
        } finally { executor.shutdown() }
    }
    output.mkdirs()
    write(File(output,"stocks.json"),stocks)
    write(File(output,"valuations.json"),mapOf("snapshotDate" to parsed.snapshotDate,"records" to valuations))
    write(File(output,"candidates.json"),mapOf("snapshotDate" to parsed.snapshotDate,"priceCutoffDate" to parsed.priceCutoffDate,
        "status" to if(candidateResult.isSuccess) "VALID" else "HOLD","error" to candidateResult.exceptionOrNull()?.let(::failure),"records" to candidates))
    val financialState=mapOf("status" to if(financialResult.isSuccess) "VALID" else "HOLD","error" to financialResult.exceptionOrNull()?.let(::failure))
    write(File(output,"financial.json"), mapOf("snapshotDate" to parsed.snapshotDate,
        "policyVersion" to FinancialSafetySnapshotParser.POLICY_VERSION,"source" to financialResult.getOrNull()?.source,
        "status" to financialState["status"],"error" to financialState["error"],
        "records" to (financialResult.getOrNull()?.records?.mapValues { (_,record) -> financialTree(record) } ?: emptyMap<String,JsonObject>())))
    val rankings=(1..15).associate { mask -> mask.toString() to DynamicRankingEngine.rank(stocks,RankMetric.entries.filterIndexed { n,_ -> mask and (1 shl n) != 0 }.map {it.id}.toSet()) }
    write(File(output,"rankings.json"),rankings)
    // Raw input observations are preserved verbatim and authenticated in the manifest.
    inputs.forEach { (name,file) -> if (file.isFile) file.copyTo(File(output,"sources/$name.json").also {it.parentFile.mkdirs()},overwrite=true) }
    var contextComplete=0; var contextPartial=0; var contextError=0; var contextUncollected=0; var historyFour=0; var historyEight=0
    stocks.forEach { stock ->
        val code=stock.issuerId
        val cachedFile=cache?.let { File(it,"$code.json") }
        val contextSource=if(code in collected) "COLLECTED" else if(cachedFile?.isFile==true) "CACHE" else "NOT_COLLECTED"
        val envelope=collected[code] ?: if(cachedFile?.isFile==true) {
            runCatching { ContextCachePolicy.validate(gson.fromJson(cachedFile.readText(),JsonObject::class.java),code) }
                .getOrElse { tree(mapOf("collectedAt" to "","evidence" to EvidenceBundle(code,loaded=true,error="INVALID_CONTEXT_CACHE: ${failure(it)}"))).asJsonObject }
        } else tree(mapOf("collectedAt" to "","evidence" to EvidenceBundle(code,loaded=false,error="CONTEXT_NOT_COLLECTED"))).asJsonObject
        val evidence=ContextCachePolicy.evidence(envelope)
        cachedFile?.takeIf {it.isFile}?.let { file ->
            val raw=file.readBytes()
            inputHashes["context/$code.json"]=hash(raw)
            File(output,"sources/context/$code.json").also {it.parentFile.mkdirs()}.writeBytes(raw)
        }
        val contextState=ContextCachePolicy.describe(envelope,contextSource)
        when {
            !evidence.loaded -> contextUncollected++
            contextState["status"].asString=="AVAILABLE" -> contextComplete++
            evidence.all.isNotEmpty() -> contextPartial++
            else -> contextError++
        }
        val history=historyResult.fold({ QuarterlyHistoryRepository.parseIssuer(it,code,"OpenDART 원본 quarterly_history.json") },{ QuarterlyHistory(code,loaded=true,error=failure(it)) })
        if(history.hasFourQuarters) historyFour++; if(history.hasEightQuarters) historyEight++
        val analysis=QuarterlyTrendAnalyzer.enrich(IntegratedInterpretationEngine.analyze(stock,evidence),history)
        val detail=tree(StockRepository.getStockDetail(code)).asJsonObject
        detail.add("analysis",tree(analysis)); detail.add("evidence",evidenceTree(evidence)); detail.add("history",historyTree(history))
        val safety=financialResult.getOrNull()?.records?.get(code) ?: FinancialSafetyRecord(code,FinancialSafetyStatus.HOLD,"PUBLICATION_UNAVAILABLE","",null,null,null,financialResult.exceptionOrNull()?.let(::failure).orEmpty())
        detail.add("financial",financialTree(safety)); detail.add("candidate",tree(candidates[code])); detail.add("valuation",tree(valuations[code]))
        detail.add("sourceStates",tree(mapOf(
            "context" to contextState,
            "financial" to mapOf("status" to if(financialResult.isSuccess) "VALID" else "HOLD","error" to financialResult.exceptionOrNull()?.let(::failure)),
            "candidate" to mapOf("status" to if(candidateResult.isSuccess) "VALID" else "HOLD","error" to candidateResult.exceptionOrNull()?.let(::failure)))))
        write(File(output,"details/$code.json"),detail)
    }
    val files=output.walkTopDown().filter {it.isFile && it.name!="manifest.json"}.sortedBy {it.path}.associate {it.relativeTo(output).invariantSeparatorsPath to mapOf("sha256" to hash(it.readBytes()),"bytes" to it.length())}
    val commit=ProcessBuilder("git","rev-parse","HEAD").directory(root).start().let {p -> val c=p.inputStream.bufferedReader().readText().trim(); require(p.waitFor()==0);c}
    val manifest=mapOf("schemaVersion" to 1,"generatedAt" to Instant.now().toString(),"sourceCommit" to commit,"snapshotDate" to parsed.snapshotDate,"priceCutoffDate" to parsed.priceCutoffDate,
        "coverage" to mapOf("stocks" to stocks.size,"details" to stocks.size,"rankingMasks" to 15,"m01Available" to parsed.m01Available,"m02Available" to parsed.m02Available,"m03Available" to parsed.m03Available,"m04Available" to parsed.m04Available,"completeCount" to parsed.completeCount,"financialValid" to financialResult.isSuccess,"financialRecords" to (financialResult.getOrNull()?.records?.size ?: 0),"candidateValid" to candidateResult.isSuccess,"candidates" to candidates.size,"historyFourQuarters" to historyFour,"historyEightQuarters" to historyEight,"contextComplete" to contextComplete,"contextPartial" to contextPartial,"contextError" to contextError,"contextNotCollected" to contextUncollected),
        "financial" to financialState,"inputHashes" to inputHashes,"files" to files)
    write(File(output,"manifest.json"),manifest)
    println("Exported ${stocks.size} stocks / ${files.size} authenticated files to $output")
    println(gson.toJson(manifest["coverage"]))
}
