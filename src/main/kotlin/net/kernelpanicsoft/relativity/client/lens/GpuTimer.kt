package net.kernelpanicsoft.relativity.client.lens

import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * Times a stretch of GL work on the GPU (debug; GameTests): [begin] and [end] round it each frame, [millis] the latest
 * result. A few queries in flight, read back only once ready, so it never stalls the frame. One stretch at a time
 * (GL_TIME_ELAPSED queries don't nest).
 */
class GpuTimer {
	private val queries = IntArray(4)
	private val pending = BooleanArray(4)
	private var next = 0

	/** The latest measured time (ms), NaN until one is ready. */
	var millis = Double.NaN
		private set

	fun begin() {
		if (queries[0] == 0) GL15.glGenQueries(queries)
		collect()
		if (pending[next]) return
		GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[next])
	}

	fun end() {
		if (pending[next]) return
		GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
		pending[next] = true
		next = (next + 1) % queries.size
	}

	private fun collect() {
		for (i in queries.indices) {
			if (!pending[i] || GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue
			millis = GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT) / 1e6
			pending[i] = false
		}
	}
}

/**
 * Like [GpuTimer], by timestamps instead of an elapsed-time query, so it can run inside another's span (time queries
 * don't nest): a span's GPU time from a timestamp at its start and end.
 */
class GpuSpanTimer {
	private val starts = IntArray(4)
	private val ends = IntArray(4)
	private val pending = BooleanArray(4)
	private var next = 0
	private var open = false

	/** The latest measured time (ms), NaN until one is ready. */
	var millis = Double.NaN
		private set

	fun begin() {
		if (starts[0] == 0) {
			GL15.glGenQueries(starts)
			GL15.glGenQueries(ends)
		}
		collect()
		if (pending[next]) return
		GL33.glQueryCounter(starts[next], GL33.GL_TIMESTAMP)
		open = true
	}

	fun end() {
		if (!open) return
		open = false
		GL33.glQueryCounter(ends[next], GL33.GL_TIMESTAMP)
		pending[next] = true
		next = (next + 1) % starts.size
	}

	private fun collect() {
		for (i in starts.indices) {
			if (!pending[i] || GL15.glGetQueryObjecti(ends[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue
			millis = (GL33.glGetQueryObjecti64(ends[i], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(starts[i], GL15.GL_QUERY_RESULT)) / 1e6
			pending[i] = false
		}
	}
}
