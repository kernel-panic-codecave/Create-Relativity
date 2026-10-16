package net.kernelpanicsoft.relativity.physics.lens

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The light a camera near a Kerr hole sees along a direction, followed back in closed form (no stepping): where it is
 * when it reaches a given radius. Kerr null geodesics are integrable; with the constants of motion lambda = L/E and
 * eta = Q/E^2 the motion separates in Mino time tau (Boyer-Lindquist):
 *
 *   (dr/dtau)^2 = R(r) = (r^2 + a^2 - a lambda)^2 - Delta (eta + (lambda - a)^2),
 *   (dtheta/dtau)^2 = Theta(theta) = eta + a^2 cos^2 theta - lambda^2 cot^2 theta,
 *   dphi/dtau = a (2 m r - a lambda) / Delta + lambda / sin^2 theta,
 *
 * and each part is an elliptic integral (Gralla & Lupsasca, Null geodesics of the Kerr exterior, 2020): the polar
 * motion exactly (cos theta = u+ sn(X | u+^2 / u-^2), its phi part a third-kind integral), the radial motion of light
 * that passes the hole exactly (its Mino time a first-kind integral from the turning point r4, r(tau) a Jacobi sn), its
 * phi part integrated along that r(tau) (smooth there). Light that falls in (no turning point outside the horizon) is
 * integrated over r (adaptively; it matters only for points by the horizon).
 *
 * Frames (pinned against NullRay): the hole's Kerr-Schild Cartesian chart of physics-core's Kerr (spin along +z), with
 * x + i y = (r + i a) sin theta e^{i phi_KS} and phi_KS = phi_BL + (a / (r+ - r-)) ln|(r - r+) / (r - r-)|. The camera
 * sees light arriving along -d; that photon's constants are the path's, and the path is followed back along +d, which
 * runs Mino time backwards: phi changes by minus its integral.
 */
class KerrRay(val m: Double, val a: Double, camera: DoubleArray, direction: DoubleArray) {
	val lambda: Double
	val eta: Double
	private val r0: Double
	private val theta0: Double
	private val phiKs0: Double
	/** Signs of dr and dtheta along the path at the camera. */
	private val radialSign: Double
	private val polarSign: Double
	private val rPlus = m + sqrt(max(m * m - a * a, 0.0))
	private val rMinus = m - sqrt(max(m * m - a * a, 0.0))
	/** The radial potential's roots (r1..r4, real parts, and whether r3, r4 are real) and the turning point. */
	val roots = DoubleArray(4)
	var rootsReal = false
		private set
	/** The path's outer radial turning point (r4), if it has one outside the horizon: NaN when it falls in. */
	val turning: Double

	init {
		val (x, y, z) = Triple(camera[0], camera[1], camera[2])
		r0 = spheroidalRadius(x, y, z)
		val cth = (z / r0).coerceIn(-1.0, 1.0)
		theta0 = acos(cth)
		val sth = sqrt(max(1 - cth * cth, 1e-300))
		phiKs0 = atan2(y, x) - atan2(a, r0)
		// The arriving photon's momentum: future-directed, spatial direction -d (Kerr-Schild: g = eta + h l l).
		val d = DoubleArray(3) { -direction[it] }
		val r2 = r0 * r0
		val h = 2 * m * r0 * r2 / (r2 * r2 + a * a * z * z)
		val l = doubleArrayOf(1.0, (r0 * x + a * y) / (r2 + a * a), (r0 * y - a * x) / (r2 + a * a), z / r0)
		fun g(mu: Int, nu: Int) = h * l[mu] * l[nu] + if (mu == nu) (if (mu == 0) -1.0 else 1.0) else 0.0
		var qa = 0.0
		var qb = 0.0
		for (i in 1..3) {
			qb += g(0, i) * d[i - 1]
			for (j in 1..3) qa += g(i, j) * d[i - 1] * d[j - 1]
		}
		val k = (-qb + sqrt(qb * qb - qa * g(0, 0))) / qa
		val u = doubleArrayOf(1.0, k * d[0], k * d[1], k * d[2])
		val p = DoubleArray(4) { mu -> (0..3).sumOf { nu -> g(mu, nu) * u[nu] } }
		val e = -p[0]
		val lz = x * p[2] - y * p[1]
		val pTheta = (cth / sth) * (x * p[1] + y * p[2]) - r0 * sth * p[3]
		val q = pTheta * pTheta + cth * cth * (lz * lz / (sth * sth) - a * a * e * e)
		lambda = lz / e
		eta = q / (e * e)
		// Along +d: which way r and theta go (their gradients).
		val step = 1e-7 * r0
		val xs = DoubleArray(3) { camera[it] + step * direction[it] }
		val rs = spheroidalRadius(xs[0], xs[1], xs[2])
		radialSign = if (rs >= r0) 1.0 else -1.0
		polarSign = if (acos((xs[2] / rs).coerceIn(-1.0, 1.0)) >= theta0) 1.0 else -1.0
		solveRoots()
		turning = if (rootsReal && roots[3] > rPlus && radialSign < 0) roots[3] else Double.NaN
	}

	private fun spheroidalRadius(x: Double, y: Double, z: Double): Double {
		val w = x * x + y * y + z * z - a * a
		return sqrt(0.5 * w + sqrt(0.25 * w * w + a * a * z * z))
	}

	private fun bigR(r: Double): Double {
		val s = r * r + a * a - a * lambda
		return s * s - (r * r - 2 * m * r + a * a) * (eta + (lambda - a) * (lambda - a))
	}

	/** Roots of R(r) = r^4 + A r^2 + B r + C (Gralla & Lupsasca eqs. 95-96, complex arithmetic). */
	private fun solveRoots() {
		val aa = a * a - eta - lambda * lambda
		val bb = 2 * m * (eta + (lambda - a) * (lambda - a))
		val cc = -a * a * eta
		val pp = -aa * aa / 12 - cc
		val qq = -aa / 3 * ((aa / 6) * (aa / 6) - cc) - bb * bb / 8
		val disc = (pp / 3) * (pp / 3) * (pp / 3) + (qq / 2) * (qq / 2)
		// omega+ + omega- (the real cube roots' sum; for a negative discriminant the complex pair's real parts).
		val sum = if (disc >= 0) {
			cbrt(-qq / 2 + sqrt(disc)) + cbrt(-qq / 2 - sqrt(disc))
		} else {
			val rho = sqrt(-(pp / 3) * (pp / 3) * (pp / 3))
			val angle = acos((-qq / 2 / rho).coerceIn(-1.0, 1.0))
			2 * cbrt(rho) * cos(angle / 3)
		}
		val z = sqrt(max((sum - aa / 3) / 2, 1e-300))
		val s1 = -aa / 2 - z * z + bb / (4 * z)
		val s2 = -aa / 2 - z * z - bb / (4 * z)
		roots[0] = -z - sqrt(max(s1, 0.0))
		roots[1] = -z + sqrt(max(s1, 0.0))
		rootsReal = s2 >= 0
		roots[2] = z - sqrt(max(s2, 0.0))
		roots[3] = z + sqrt(max(s2, 0.0))
	}

	/**
	 * Where the path is (Kerr-Schild Cartesian) when it first reaches radius [radius] on the way in ([outbound] false;
	 * only short of a turning point) or, past its turning point, on the way out; null if it doesn't.
	 */
	fun at(radius: Double, outbound: Boolean): DoubleArray? {
		val tau: Double
		val radialPhi: Double
		if (!turning.isNaN()) {
			if (radius < turning) return null
			// Mino time from the turning point to r (first kind), r(tau) by Jacobi sn, phi along it by Gauss-Legendre.
			val r1 = roots[0]; val r3 = roots[2]; val r4 = roots[3]
			val r31 = r3 - r1; val r41 = r4 - r1; val r32 = r3 - roots[1]; val r42 = r4 - roots[1]
			val k = r32 * r41 / (r31 * r42)
			val scale = 2 / sqrt(r31 * r42)
			fun fromTurning(r: Double) = scale * ellipticF(asin(sqrt(max((r - r4) * r31 / ((r - r3) * r41), 0.0)).coerceAtMost(1.0)), k)
			fun rAt(t: Double): Double { val sn = jacobiSn(t / scale, k); return (r4 * r31 - r3 * r41 * sn * sn) / (r31 - r41 * sn * sn) }
			val toTurn = fromTurning(r0)
			val back = fromTurning(radius)
			if (!outbound && radius > r0) return null
			tau = if (outbound) toTurn + back else toTurn - back
			// phi's radial part: the integral of a (2 m r - a lambda) / Delta over Mino time, each leg from the turning
			// point; in closed form (third kind) or, for checking, by quadrature along r(tau).
			radialPhi = if (radialQuadrature) {
				fun radialRate(r: Double) = a * (2 * m * r - a * lambda) / (r * r - 2 * m * r + a * a)
				if (outbound) gauss(0.0, toTurn) { radialRate(rAt(it)) } + gauss(0.0, back) { radialRate(rAt(it)) }
					else gauss(back, toTurn) { radialRate(rAt(it)) }
			} else {
				val fromCamera = radialPhiFromTurning(r0, k, scale)
				val fromPoint = radialPhiFromTurning(radius, k, scale)
				if (outbound) fromCamera + fromPoint else fromCamera - fromPoint
			}
		} else if (radialSign > 0) {
			// Heading out from the start (looking away from the hole): r grows all the way.
			if (!outbound || radius <= r0) return null
			val (t, phi) = monotonic(r0, radius)
			tau = t; radialPhi = phi
		} else {
			if (outbound || radius >= r0 || radius <= rPlus) return null
			// Falling in: r shrinks all the way.
			val (t, phi) = monotonic(radius, r0)
			tau = t; radialPhi = phi
		}
		val (theta, polarPhi) = polar(tau) ?: return null
		// Back along the path Mino time runs backwards: phi changes by minus the integrals.
		val phiBl = -(radialPhi + lambda * polarPhi)
		fun kPhi(r: Double) = if (abs(a) < 1e-12) 0.0 else a / (rPlus - rMinus) * ln(abs((r - rPlus) / (r - rMinus)))
		val phiKs = phiKs0 + phiBl + kPhi(radius) - kPhi(r0)
		val st = sin(theta)
		return doubleArrayOf(st * (radius * cos(phiKs) - a * sin(phiKs)), st * (radius * sin(phiKs) + a * cos(phiKs)), radius * cos(theta))
	}

	/** Checking: phi's radial part by quadrature instead of the closed form. */
	var radialQuadrature = false

	/**
	 * Phi's radial part from the turning point r4 out to [r] (light that passes the hole): the integral of
	 * a (A+ / (r - r+) + A- / (r - r-)) over Mino time, A+- = +-(2 m r+- - a lambda) / (r+ - r-). With
	 * sn^2 X = (r - r4) r31 / ((r - r3) r41), 1 / (r - r+-) = 1 / (r3 - r+-) - r43 / ((r4 - r+-)(r3 - r+-)) / (1 - n sn^2 X),
	 * n = r41 (r3 - r+-) / (r31 (r4 - r+-)) (< 1: r+- > r1), so each term is F and Pi at amplitude arcsin sqrt(...).
	 */
	private fun radialPhiFromTurning(r: Double, k: Double, scale: Double): Double {
		val r1 = roots[0]; val r3 = roots[2]; val r4 = roots[3]
		val r31 = r3 - r1; val r41 = r4 - r1; val r43 = r4 - r3
		val amplitude = asin(sqrt(max((r - r4) * r31 / ((r - r3) * r41), 0.0)).coerceAtMost(1.0))
		val f = ellipticF(amplitude, k)
		fun term(rh: Double): Double {
			val n = r41 * (r3 - rh) / (r31 * (r4 - rh))
			return scale * (f / (r3 - rh) - r43 / ((r4 - rh) * (r3 - rh)) * ellipticPi(n, amplitude, k))
		}
		if (abs(rPlus - rMinus) < 1e-9) return 0.0
		val aPlus = (2 * m * rPlus - a * lambda) / (rPlus - rMinus)
		val aMinus = -(2 * m * rMinus - a * lambda) / (rPlus - rMinus)
		return a * (aPlus * term(rPlus) + aMinus * term(rMinus))
	}

	/**
	 * Mino time and phi's radial integral between radii [lo] < [hi] for light whose r changes monotonically there:
	 * over w = 1 / r (the integrands fall off as 1 / r^2 far out), adaptively.
	 */
	private fun monotonic(lo: Double, hi: Double): Pair<Double, Double> {
		fun rate(r: Double) = a * (2 * m * r - a * lambda) / (r * r - 2 * m * r + a * a)
		val t = adaptive(1 / hi, 1 / lo) { w -> val r = 1 / w; r * r / sqrt(max(bigR(r), 1e-300)) }
		val phi = adaptive(1 / hi, 1 / lo) { w -> val r = 1 / w; rate(r) * r * r / sqrt(max(bigR(r), 1e-300)) }
		return t to phi
	}

	/** Where the path turns (its closest approach, Kerr-Schild Cartesian), or null if it falls in. */
	fun closest(): DoubleArray? = if (turning.isNaN()) null else at(turning * (1 + 1e-9), true)

	/**
	 * Theta after Mino time [tau] along the path, and the integral of 1 / sin^2 theta over it; null for vortical light
	 * (eta < 0, round a pole: not handled yet).
	 */
	private fun polar(tau: Double): Pair<Double, Double>? {
		if (eta <= 0.0) return null
		val a2 = max(a * a, 1e-18)
		val dth = 0.5 * (1 - (eta + lambda * lambda) / a2)
		val root = sqrt(dth * dth + eta / a2)
		val uPlus2 = min(dth + root, 1 - 1e-12) // (just short of the pole: light passing over it)
		val uMinus2 = dth - root
		val uPlus = sqrt(max(uPlus2, 0.0))
		val mm = uPlus2 / uMinus2
		val rate = sqrt(a2 * -uMinus2)
		val u0 = cos(theta0)
		// cos theta = u+ sn(X | m), X = X0 - (theta's direction) rate tau: theta increasing is u decreasing.
		val x0 = ellipticF(asin((u0 / uPlus).coerceIn(-1.0, 1.0)), mm)
		val x = x0 - polarSign * rate * tau
		val u = uPlus * jacobiSn(x, mm)
		// Integral of dtau / (1 - u^2): (Pi(X0) - Pi(X)) / (direction * rate), Pi the third kind in X.
		val integral = (piOf(x0, uPlus2, mm) - piOf(x, uPlus2, mm)) / (polarSign * rate)
		return acos(u.coerceIn(-1.0, 1.0)) to integral
	}

	companion object {
		/**
		 * The image of [point] (Kerr-Schild Cartesian, from the hole) seen from [camera]: the view direction whose light,
		 * followed back, reaches it; Levenberg-Marquardt on the closed-form path from each of [starts] in turn (each on the
		 * leg the start lands nearer on, then the other), until one lands within [tolerance] (blocks). Null if none does.
		 */
		fun image(m: Double, a: Double, camera: DoubleArray, point: DoubleArray, starts: List<DoubleArray>, tolerance: Double = 1e-4): DoubleArray? {
			val w = point[0] * point[0] + point[1] * point[1] + point[2] * point[2] - a * a
			val radius = sqrt(0.5 * w + sqrt(0.25 * w * w + a * a * point[2] * point[2]))
			fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
			fun miss(hit: DoubleArray) = sqrt((0..2).sumOf { (hit[it] - point[it]) * (hit[it] - point[it]) })
			fun solve(outbound: Boolean, start: DoubleArray): DoubleArray? {
				var dir = start
				fun residual(d: DoubleArray): DoubleArray? {
					val ray = KerrRay(m, a, camera, d)
					val hit = ray.at(radius, outbound) ?: ray.closest() ?: return null
					return DoubleArray(3) { hit[it] - point[it] }
				}
				var damping = 1e-3
				var rv = residual(dir) ?: return null
				for (iteration in 0 until 16) {
					val missNow = norm(rv)
					if (missNow < tolerance) return dir
					val helper = if (abs(dir[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
					val c = doubleArrayOf(dir[1] * helper[2] - dir[2] * helper[1], dir[2] * helper[0] - dir[0] * helper[2], dir[0] * helper[1] - dir[1] * helper[0])
					val cn = norm(c)
					val e1 = DoubleArray(3) { c[it] / cn }
					val e2 = doubleArrayOf(dir[1] * e1[2] - dir[2] * e1[1], dir[2] * e1[0] - dir[0] * e1[2], dir[0] * e1[1] - dir[1] * e1[0])
					val eps = 1e-6
					val ra = residual(DoubleArray(3) { dir[it] + eps * e1[it] }) ?: return null
					val rb = residual(DoubleArray(3) { dir[it] + eps * e2[it] }) ?: return null
					val ja = DoubleArray(3) { (ra[it] - rv[it]) / eps }
					val jb = DoubleArray(3) { (rb[it] - rv[it]) / eps }
					val aa0 = ja.indices.sumOf { ja[it] * ja[it] }; val ab = ja.indices.sumOf { ja[it] * jb[it] }; val bb0 = jb.indices.sumOf { jb[it] * jb[it] }
					val ga = ja.indices.sumOf { ja[it] * rv[it] }; val gb = jb.indices.sumOf { jb[it] * rv[it] }
					var accepted = false
					for (tries in 0 until 8) {
						val aa = aa0 * (1 + damping); val bb = bb0 * (1 + damping)
						val det = aa * bb - ab * ab
						if (abs(det) < 1e-300) break
						val da = -(bb * ga - ab * gb) / det; val db = -(aa * gb - ab * ga) / det
						val nd = DoubleArray(3) { dir[it] + da * e1[it] + db * e2[it] }; val nn = norm(nd)
						val trial = DoubleArray(3) { nd[it] / nn }
						val tr = residual(trial)
						if (tr != null && norm(tr) < missNow) { dir = trial; rv = tr; damping = max(damping / 4, 1e-6); accepted = true; break }
						damping *= 8
					}
					if (!accepted) return null
				}
				return null
			}
			for (start in starts) {
				val ray = KerrRay(m, a, camera, start)
				val first = listOf(false, true).minByOrNull { leg -> ray.at(radius, leg)?.let { miss(it) } ?: 1e30 }!!
				solve(first, start)?.let { return it }
				solve(!first, start)?.let { return it }
			}
			return null
		}

		/** Carlson's R_F (duplication). */
		fun carlsonRF(x0: Double, y0: Double, z0: Double): Double {
			var x = x0; var y = y0; var z = z0
			repeat(40) {
				val sx = sqrt(x); val sy = sqrt(y); val sz = sqrt(z)
				val lam = sx * (sy + sz) + sy * sz
				x = 0.25 * (x + lam); y = 0.25 * (y + lam); z = 0.25 * (z + lam)
				val mu = (x + y + z) / 3
				if (max(abs(x - mu), max(abs(y - mu), abs(z - mu))) < 1e-10 * mu) {
					val dx = (mu - x) / mu; val dy = (mu - y) / mu; val dz = (mu - z) / mu
					val e2 = dx * dy - dz * dz; val e3 = dx * dy * dz
					return (1 + (e2 / 24 - 0.1 - 3 * e3 / 44) * e2 + e3 / 14) / sqrt(mu)
				}
			}
			val mu = (x + y + z) / 3
			return 1 / sqrt(mu)
		}

		/** Carlson's R_C. */
		fun carlsonRC(x0: Double, y0: Double): Double {
			if (y0 == 0.0) return 1e30 // (the logarithmic edge: light over the pole)
			if (y0 < 0) return sqrt(x0 / (x0 - y0)) * carlsonRC(x0 - y0, -y0)
			var x = x0; var y = y0
			repeat(40) {
				val lam = 2 * sqrt(x) * sqrt(y) + y
				x = 0.25 * (x + lam); y = 0.25 * (y + lam)
				val mu = (x + 2 * y) / 3
				val s = (y - mu) / mu
				if (abs(s) < 1e-10) return (1 + s * s * (0.3 + s * (1.0 / 7 + s * (0.375 + s * 9.0 / 22)))) / sqrt(mu)
			}
			return 1 / sqrt((x + 2 * y) / 3)
		}

		/** Carlson's R_J (p > 0). */
		fun carlsonRJ(x0: Double, y0: Double, z0: Double, p0: Double): Double {
			var x = x0; var y = y0; var z = z0; var p = p0
			var sum = 0.0
			var fac = 1.0
			repeat(40) {
				val sx = sqrt(x); val sy = sqrt(y); val sz = sqrt(z)
				val lam = sx * (sy + sz) + sy * sz
				val alpha = (p * (sx + sy + sz) + sx * sy * sz).let { it * it }
				val beta = p * (p + lam) * (p + lam)
				sum += fac * carlsonRC(alpha, beta)
				fac *= 0.25
				x = 0.25 * (x + lam); y = 0.25 * (y + lam); z = 0.25 * (z + lam); p = 0.25 * (p + lam)
				val mu = (x + y + z + 2 * p) / 5
				if (max(max(abs(x - mu), abs(y - mu)), max(abs(z - mu), abs(p - mu))) < 1e-9 * mu) {
					val dx = (mu - x) / mu; val dy = (mu - y) / mu; val dz = (mu - z) / mu; val dp = (mu - p) / mu
					val ea = dx * (dy + dz) + dy * dz; val eb = dx * dy * dz; val ec = dp * dp
					val e2 = ea - 3 * ec; val e3 = eb + 2 * dp * (ea - ec)
					val e4 = (2 * eb + dp * (ea - 3 * ec)) * dp; val e5 = eb * dp * dp
					return 3 * sum + fac * (1 - 3 * e2 / 14 + e3 / 6 + 9 * e2 * e2 / 88 - 3 * e4 / 22 - 9 * e2 * e3 / 52 + 3 * e5 / 26) / (mu * sqrt(mu))
				}
			}
			return 3 * sum
		}

		/** F(phi | m) for any real phi (periodic extension), any m < 1. */
		fun ellipticF(phi: Double, m: Double): Double {
			val j = floor(phi / PI + 0.5)
			val rest = phi - j * PI
			val s = sin(rest); val c = cos(rest)
			val part = s * carlsonRF(c * c, 1 - m * s * s, 1.0)
			return if (j == 0.0) part else part + 2 * j * carlsonRF(0.0, 1 - m, 1.0)
		}

		/** Pi(n; phi | m) for any real phi (periodic extension), n < 1, m < 1. */
		fun ellipticPi(n: Double, phi: Double, m: Double): Double {
			val j = floor(phi / PI + 0.5)
			val rest = phi - j * PI
			val s = sin(rest); val c = cos(rest)
			val part = s * carlsonRF(c * c, 1 - m * s * s, 1.0) + n / 3 * s * s * s * carlsonRJ(c * c, 1 - m * s * s, 1.0, 1 - n * s * s)
			if (j == 0.0) return part
			val full = carlsonRF(0.0, 1 - m, 1.0) + n / 3 * carlsonRJ(0.0, 1 - m, 1.0, 1 - n)
			return part + 2 * j * full
		}

		/**
		 * The Jacobi amplitude am(x | m) by the AGM (descending Landen, Abramowitz & Stegun 16.4), any m < 1: a negative
		 * parameter through the imaginary-modulus transformation (sn(x | m) = sd(x sqrt(1 - m) | -m / (1 - m)) / sqrt(1 - m)).
		 * Continuous in x (each half period adds pi).
		 */
		fun jacobiAmAgm(x: Double, m: Double): Double {
			if (m < 0) {
				val k = 1 - m
				val mu = -m / k
				val v = x * sqrt(k)
				val phi = jacobiAmAgm(v, mu)
				// sn = sd(v | mu) / sqrt(k) = sin(phi) / (sqrt(1 - mu sin^2 phi) sqrt(k)); cn = cos(phi) / sqrt(1 - mu sin^2 phi) (cd).
				val sp = sin(phi); val cp = cos(phi)
				val dn = sqrt(1 - mu * sp * sp)
				val sn = sp / (dn * sqrt(k)); val cn = cp / dn
				// Same quarter periods as phi (the transformation keeps the zeros of sn and cn).
				return atan2(sn, cn) + 2 * PI * floor((phi + PI) / (2 * PI))
			}
			val aa = DoubleArray(12); val cc = DoubleArray(12)
			aa[0] = 1.0; var b = sqrt(1 - m); cc[0] = sqrt(m)
			var n = 0
			while (n < 11 && abs(cc[n]) > 1e-15) {
				aa[n + 1] = 0.5 * (aa[n] + b); cc[n + 1] = 0.5 * (aa[n] - b); b = sqrt(aa[n] * b)
				n += 1
			}
			var phi = (1 shl n) * aa[n] * x
			for (i in n downTo 1) phi = 0.5 * (phi + asin((cc[i] / aa[i] * sin(phi)).coerceIn(-1.0, 1.0)))
			return phi
		}

		/** am(x | m) (jacobiAmAgm; an earlier Newton inversion of F diverged as m -> 1). */
		fun jacobiAm(x: Double, m: Double) = jacobiAmAgm(x, m)

		fun jacobiSn(x: Double, m: Double) = sin(jacobiAm(x, m))

		/** The integral of 1 / (1 - n sn^2) from 0 to X: Pi(n; am X | m). */
		private fun piOf(x: Double, n: Double, m: Double) = ellipticPi(n, jacobiAm(x, m), m)

		private val GL_X = doubleArrayOf(-0.9894009349916499, -0.9445750230732326, -0.8656312023878318, -0.7554044083550030, -0.6178762444026438, -0.4580167776572274, -0.2816035507792589, -0.0950125098376374, 0.0950125098376374, 0.2816035507792589, 0.4580167776572274, 0.6178762444026438, 0.7554044083550030, 0.8656312023878318, 0.9445750230732326, 0.9894009349916499)
		private val GL_W = doubleArrayOf(0.0271524594117541, 0.0622535239386479, 0.0951585116824928, 0.1246289712555339, 0.1495959888165767, 0.1691565193950025, 0.1826034150449236, 0.1894506104550685, 0.1894506104550685, 0.1826034150449236, 0.1691565193950025, 0.1495959888165767, 0.1246289712555339, 0.0951585116824928, 0.0622535239386479, 0.0271524594117541)

		/** 16-point Gauss-Legendre, split into 4 pieces. */
		fun gauss(lo: Double, hi: Double, f: (Double) -> Double): Double {
			var sum = 0.0
			val pieces = 4
			for (p in 0 until pieces) {
				val a = lo + (hi - lo) * p / pieces; val b = lo + (hi - lo) * (p + 1) / pieces
				val mid = 0.5 * (a + b); val half = 0.5 * (b - a)
				for (i in GL_X.indices) sum += GL_W[i] * half * f(mid + half * GL_X[i])
			}
			return sum
		}

		/** Adaptive Simpson. */
		fun adaptive(lo: Double, hi: Double, f: (Double) -> Double): Double {
			fun simpson(a: Double, b: Double, fa: Double, fm: Double, fb: Double) = (b - a) / 6 * (fa + 4 * fm + fb)
			fun rec(a: Double, b: Double, fa: Double, fm: Double, fb: Double, whole: Double, depth: Int): Double {
				val m = 0.5 * (a + b); val lm = 0.5 * (a + m); val rm = 0.5 * (m + b)
				val flm = f(lm); val frm = f(rm)
				val left = simpson(a, m, fa, flm, fm); val right = simpson(m, b, fm, frm, fb)
				return if (depth > 40 || abs(left + right - whole) < 1e-10 * max(1.0, abs(whole))) left + right + (left + right - whole) / 15
					else rec(a, m, fa, flm, fm, left, depth + 1) + rec(m, b, fm, frm, fb, right, depth + 1)
			}
			val fa = f(lo); val fb = f(hi); val fm = f(0.5 * (lo + hi))
			return rec(lo, hi, fa, fm, fb, simpson(lo, hi, fa, fm, fb), 0)
		}
	}
}
