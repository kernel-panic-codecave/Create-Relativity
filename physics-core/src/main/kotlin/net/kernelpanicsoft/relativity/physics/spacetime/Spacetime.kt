package net.kernelpanicsoft.relativity.physics.spacetime

/**
 * A spacetime in some coordinate chart `x^μ`, in geometric units: `c = G = 1`, lengths in blocks.
 *
 * Arrays are flat and caller-owned so the hot path allocates nothing:
 * the metric is row-major `g[μ * 4 + ν]`, the connection is `Γ[λ * 16 + μ * 4 + ν]` for `Γ^λ_{μν}`.
 */
interface Spacetime {
	/** Covariant metric `g_{μν}` at [x] into [out] (16 entries). */
	fun metric(x: DoubleArray, out: DoubleArray)

	/** Christoffel symbols `Γ^λ_{μν}` at [x] into [out] (64 entries). */
	fun christoffel(x: DoubleArray, out: DoubleArray)
}

/** Index of `Γ^λ_{μν}` in a flat connection array. */
@Suppress("NOTHING_TO_INLINE")
inline fun gammaIndex(lambda: Int, mu: Int, nu: Int): Int = lambda * 16 + mu * 4 + nu
