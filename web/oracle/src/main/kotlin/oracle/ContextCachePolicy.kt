package oracle

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.krstock.v3.data.evidence.DisclosureDiffEngine
import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.EvidenceSourceTier
import java.net.URI
import java.time.Instant

/** Publication I/O policy only: retain observed rows on transport loss, never invent source content. */
object ContextCachePolicy {
    private val json = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val categories = listOf("news", "disclosures", "ir", "official")
    private fun text(o: JsonObject?, key: String): String = o?.get(key)?.takeUnless {it.isJsonNull}?.asString.orEmpty()
    private fun rows(e: EvidenceBundle, category: String): List<ContextEvidence> = when(category) {
        "news" -> e.news; "disclosures" -> e.disclosures; "ir" -> e.ir; else -> e.official
    }
    private fun identity(e: ContextEvidence): String = if (canonicalDart(e)) "receipt:${e.receiptNo}" else "${e.kind}:${e.id}:${e.url}:${e.title}"
    private fun canonicalDart(e: ContextEvidence): Boolean = runCatching {
        val u=URI(e.url)
        e.sourceTier==EvidenceSourceTier.DART_PRIMARY && e.receiptNo.matches(Regex("\\d{12,16}")) &&
            u.scheme=="https" && u.host=="dart.fss.or.kr" && u.path=="/dsaf001/main.do" &&
            u.rawQuery.orEmpty().split('&').contains("rcpNo=${e.receiptNo}")
    }.getOrDefault(false)
    private fun categoryError(category: String, error: String?): String? {
        if(error.isNullOrBlank()) return null
        val prefixes=when(category) { "news" -> listOf("news="); "disclosures" -> listOf("dart=","naverDisclosure="); else -> listOf("official=") }
        return error.split(", ").filter { part -> prefixes.any {part.startsWith(it)} }.joinToString(", ").ifBlank { if(!error.contains('=')) error else "" }.takeIf {it.isNotBlank()}
    }
    private fun mayRetain(category: String, row: ContextEvidence, error: String?): Boolean {
        if(error.isNullOrBlank()) return false
        if(!error.contains('=')) return true // Whole-fetch transport failure.
        return when(category) {
            "news" -> error.contains("news=")
            "disclosures" -> if(canonicalDart(row)) error.split(", ").any {it.startsWith("dart=")} else error.contains("naverDisclosure=")
            else -> error.contains("official=")
        }
    }

    @JvmStatic fun evidence(envelope: JsonObject): EvidenceBundle = json.fromJson(envelope["evidence"],EvidenceBundle::class.java)

    @JvmStatic fun validate(envelope: JsonObject, code: String): JsonObject {
        require(envelope["schemaVersion"].asInt==1 && text(envelope,"issuerId")==code)
        Instant.parse(text(envelope,"collectedAt"))
        val e=evidence(envelope)
        require(e.issuerId==code && e.loaded)
        e.all.forEach { row ->
            // Original cache fields must be present; Gson must not turn a malformed
            // input into a partially populated Kotlin record used for retention.
            require(row.title.isNotBlank()); row.bodyText.length; row.url.length; row.receiptNo.length; row.sourceTier.priority
        }
        envelope.get("categoryStates")?.takeUnless {it.isJsonNull}?.asJsonObject?.let { states ->
            for(category in categories) states.get(category)?.takeUnless {it.isJsonNull}?.asJsonObject?.let { state ->
                require(state["count"].asInt==rows(e,category).size)
                Instant.parse(text(state,"checkedAt")); Instant.parse(text(state,"lastAttemptAt"))
                require(state["retainedCount"].asInt in 0..rows(e,category).size)
                state.getAsJsonObject("itemObservedAt")?.entrySet()?.forEach { (_,value) -> Instant.parse(value.asString) }
            }
        }
        return envelope
    }

    private fun observedAt(previous: JsonObject?, category: String, key: String): String {
        val state=previous?.getAsJsonObject("categoryStates")?.getAsJsonObject(category)
        return text(state?.getAsJsonObject("itemObservedAt"),key).ifBlank {
            text(state,"checkedAt").ifBlank { text(previous,"collectedAt") }
        }
    }

    /** Merge only missing/less-complete observations on a failed category or lost hydrated body. */
    @JvmStatic fun refresh(previous: JsonObject?, incoming: EvidenceBundle, attemptedAt: String): JsonObject {
        Instant.parse(attemptedAt)
        previous?.let { validate(it,incoming.issuerId) }
        val old=previous?.let(::evidence)
        val merged=linkedMapOf<String,List<ContextEvidence>>()
        val states=JsonObject()
        var anyRetained=false
        for(category in categories) {
            val newRows=rows(incoming,category)
            val byId=newRows.associateByTo(linkedMapOf(),::identity)
            val retained=linkedSetOf<String>()
            for(row in old?.let { rows(it,category) }.orEmpty()) {
                val key=identity(row); val replacement=byId[key]
                val bodyLost=canonicalDart(row) && row.bodyText.length>=400 && replacement!=null && replacement.bodyText.length<400
                if(bodyLost || (replacement==null && mayRetain(category,row,incoming.error))) {
                    byId[key]=row; retained+=key
                }
            }
            val result=byId.values.toList(); merged[category]=result
            val error=categoryError(category,incoming.error) ?: if(retained.isNotEmpty()) "CONTEXT_REFRESH_EVIDENCE_LOSS" else null
            val itemTimes=byId.keys.associateWith { key -> if(key in retained) observedAt(previous,category,key) else attemptedAt }
            anyRetained=anyRetained || retained.isNotEmpty()
            val state=JsonObject().apply {
                addProperty("loaded",incoming.loaded || old?.loaded==true); addProperty("count",result.size)
                addProperty("status",when { result.isEmpty()->"MISSING"; retained.isNotEmpty()->"PARTIAL_OR_CACHE"; error!=null->"PARTIAL"; else->"AVAILABLE" })
                addProperty("source",if(retained.size==result.size && result.isNotEmpty()) "CACHE" else if(retained.isNotEmpty()) "MIXED" else "COLLECTED")
                addProperty("checkedAt",itemTimes.values.minOrNull() ?: attemptedAt)
                addProperty("lastAttemptAt",attemptedAt); addProperty("lastAttemptError",error)
                addProperty("retainedCount",retained.size); add("retainedIds",json.toJsonTree(retained)); add("itemObservedAt",json.toJsonTree(itemTimes))
            }
            states.add(category,state)
        }
        val attemptError=incoming.error ?: if(anyRetained) "CONTEXT_REFRESH_EVIDENCE_LOSS" else null
        val result=EvidenceBundle(incoming.issuerId,merged.getValue("news"),merged.getValue("disclosures"),merged.getValue("ir"),merged.getValue("official"),
            DisclosureDiffEngine.compare(merged.getValue("disclosures")),incoming.loaded || old?.loaded==true,attemptError)
        val earliest=if(anyRetained) categories.map {text(states.getAsJsonObject(it),"checkedAt")}.filter {it.isNotBlank()}.minOrNull() ?: attemptedAt else attemptedAt
        return JsonObject().apply {
            addProperty("schemaVersion",1); addProperty("issuerId",incoming.issuerId)
            addProperty("collectedAt",earliest); addProperty("lastAttemptAt",attemptedAt); addProperty("lastAttemptError",attemptError)
            addProperty("collector","original ContextEvidenceRepository"); addProperty("retainedPrevious",anyRetained)
            add("categoryStates",states)
            add("evidence",json.toJsonTree(result).asJsonObject.apply {
                add("all",json.toJsonTree(result.all)); addProperty("primarySourceCount",result.primarySourceCount)
            })
        }
    }

    /** Describe each category independently, including zero-item categories in older envelopes. */
    @JvmStatic fun describe(envelope: JsonObject, source: String): JsonObject {
        val e=evidence(envelope); val checked=text(envelope,"collectedAt")
        val states=JsonObject()
        for(category in categories) {
            val saved=envelope.getAsJsonObject("categoryStates")?.getAsJsonObject(category)
            val count=rows(e,category).size
            val error=if(saved!=null) text(saved,"lastAttemptError").takeIf {it.isNotBlank()} else categoryError(category,e.error)
            val state=saved?.deepCopy() ?: JsonObject().apply {
                addProperty("loaded",e.loaded); addProperty("count",count)
                addProperty("checkedAt",checked); addProperty("lastAttemptAt",text(envelope,"lastAttemptAt").ifBlank {checked})
                addProperty("lastAttemptError",error); addProperty("retainedCount",0)
                add("itemObservedAt",json.toJsonTree(rows(e,category).associate {identity(it) to checked}))
            }
            state.addProperty("status",when {count==0->"MISSING"; saved!=null && text(saved,"status")=="PARTIAL_OR_CACHE"->"PARTIAL_OR_CACHE"; error!=null->"PARTIAL"; source=="CACHE"->"CACHE"; else->"AVAILABLE"})
            if(source=="CACHE") state.addProperty("source","CACHE") else if(!state.has("source")) state.addProperty("source",source)
            states.add(category,state)
        }
        val hasRows=e.all.isNotEmpty()
        val retained=envelope.get("retainedPrevious")?.asBoolean==true
        val overall=when {
            !e.loaded -> "NOT_COLLECTED"
            hasRows && (retained || source=="CACHE") -> "PARTIAL_OR_CACHE"
            hasRows && (e.error!=null || categories.any {rows(e,it).isEmpty()}) -> "PARTIAL"
            hasRows -> "AVAILABLE"
            e.error!=null -> "ERROR"
            else -> "MISSING"
        }
        return JsonObject().apply {
            addProperty("status",overall)
            val hasFresh=categories.any { val category=states.getAsJsonObject(it); category["count"].asInt>0 && text(category,"source") in setOf("COLLECTED","MIXED") }
            addProperty("source",if(retained && source=="COLLECTED") {if(hasFresh) "MIXED" else "CACHE"} else source)
            addProperty("checkedAt",checked); addProperty("error",e.error)
            addProperty("lastAttemptAt",text(envelope,"lastAttemptAt").ifBlank {checked})
            addProperty("lastAttemptError",text(envelope,"lastAttemptError").takeIf {it.isNotBlank()})
            add("categories",states)
        }
    }
}
