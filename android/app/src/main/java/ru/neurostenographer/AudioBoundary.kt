package ru.neurostenographer

/** Предпочитаем тихую границу в конце окна; при непрерывной речи остаётся номинальная. */
internal object AudioBoundary {
    fun endMs(samples:FloatArray,startMs:Long,nominalEndMs:Long,lookbackMs:Long):Long {
        val first=maxOf(startMs+1000,nominalEndMs-lookbackMs)
        var time=nominalEndMs-200
        while(time>=first) {
            val from=(time*16).toInt().coerceIn(0,samples.size)
            val to=((time+200)*16).toInt().coerceIn(from,samples.size)
            if(to>from) {
                var sum=0.0; for(i in from until to) sum+=samples[i]*samples[i]
                if(sum/(to-from)<0.000064) return time+100
            }
            time-=100
        }
        return nominalEndMs
    }
}
