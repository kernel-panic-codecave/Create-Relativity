package net.kernelpanicsoft.relativity.physics.accretion

/**
 * Radiation pressure from a feeding hole. Luminosity is L = η Ṁ c²; the Eddington luminosity is where its outward
 * push on infalling matter equals gravity's pull. Both fall off as 1/r², so the push is gravity scaled by the
 * Eddington ratio λ = Ṁ / Ṁ_Edd, everywhere at once: at λ = 1 loose matter hangs in balance, above it it's blown away,
 * which is what caps how fast a hole can feed.
 */
object Eddington {
	/** λ = Ṁ / Ṁ_Edd. */
	fun ratio(accretionRate: Double, eddingtonRate: Double): Double = if (eddingtonRate <= 0.0) 0.0 else accretionRate / eddingtonRate

	/**
	 * Outward radiation acceleration as a fraction of the Newtonian pull, for matter with radiation [coupling]
	 * (1 for loose, optically thin debris; less for dense, compact things that intercept little light per unit mass).
	 */
	fun pushOverGravity(ratio: Double, coupling: Double): Double = ratio * coupling
}
