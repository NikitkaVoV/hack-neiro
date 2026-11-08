package ru.neurostenographer

import org.json.JSONArray
import kotlin.math.sqrt

/** Сохраняемые центроиды. Порог — эксперимент; идентичность людей не устанавливается. */
internal class SpeakerMatcher(
    saved: JSONArray? = null,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val allowManyToOne: Boolean = false,
    private val manyToOneThreshold: Float = MANY_TO_ONE_THRESHOLD,
) {
    private val centers=mutableListOf<FloatArray>()
    init { require(threshold in 0f..1f) { "Порог сопоставления голосов должен быть от 0 до 1" } }
    init { require(manyToOneThreshold in 0f..1f) { "Порог объединения голосов должен быть от 0 до 1" } }
    init { saved?.let { for(i in 0 until it.length()) { val a=it.getJSONArray(i); centers.add(FloatArray(a.length()) { j -> a.getDouble(j).toFloat() }) } } }
    fun match(vectors: Map<String,FloatArray>, turns: List<SpeakerTurn> = emptyList()): Map<String,String> {
        val used=mutableSetOf<Int>(); val result=mutableMapOf<String,String>()
        val assignedInWindow=mutableMapOf<Int,MutableList<String>>()
        val turnsBySpeaker=turns.groupBy { it.speakerId }
        for((id,raw) in vectors) {
            val vector=normalize(raw)
            val scores=centers.mapIndexed { i,v -> i to if(v.size==vector.size) v.indices.sumOf { (v[it]*vector[it]).toDouble() }.toFloat() else -1f }
            val best=scores.filter { (center, similarity) ->
                similarity >= threshold && (center !in used ||
                    (allowManyToOne && similarity >= manyToOneThreshold &&
                        !overlapsInWindow(id, assignedInWindow[center].orEmpty(), turnsBySpeaker)))
            }.maxByOrNull { it.second }
            val index=if(best!=null) {
                val i=best.first; centers[i]=normalize(FloatArray(vector.size) { (centers[i][it]+vector[it])/2 }); i
            } else { centers.add(vector); centers.lastIndex }
            used.add(index)
            assignedInWindow.getOrPut(index) { mutableListOf() }.add(id)
            result[id]="speaker_${index+1}"
        }
        return result
    }
    private fun overlapsInWindow(
        candidateId: String,
        assignedIds: List<String>,
        turnsBySpeaker: Map<String, List<SpeakerTurn>>,
    ): Boolean {
        val candidateTurns=turnsBySpeaker[candidateId].orEmpty()
        // Missing timing evidence must never make a many-to-one merge look safe.
        if (candidateTurns.isEmpty()) return true
        return assignedIds.any { assignedId ->
            val assignedTurns=turnsBySpeaker[assignedId].orEmpty()
            if (assignedTurns.isEmpty()) return@any true
            candidateTurns.any { candidate -> assignedTurns.any { assigned ->
                candidate.startMs < assigned.endMs && assigned.startMs < candidate.endMs
            } }
        }
    }
    fun toJson()=JSONArray().apply { centers.forEach { put(JSONArray(it.toList())) } }
    companion object {
        const val DEFAULT_THRESHOLD = 0.55f
        const val MANY_TO_ONE_THRESHOLD = 0.75f
        fun normalize(v:FloatArray):FloatArray { val norm=sqrt(v.sumOf { (it*it).toDouble() }).toFloat().coerceAtLeast(1e-9f); return FloatArray(v.size) { v[it]/norm } }
    }
}
