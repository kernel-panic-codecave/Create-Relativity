# Create: Relativity — Design & Implementation Plan

A Create: Aeronautics addon (Kotlin, NeoForge 1.21.1) that adds special and general relativity to the world:
black holes that accrete and grow, frame dragging, time dilation, length contraction, traversable wormholes,
and spaghettification, with shader visuals that show what's actually going on.

---

## 0. Ground rules

### What "full GR" means here
Solving the Einstein field equations live (numerical relativity) won't fit in a game tick. Instead:

- **Spacetime = analytic exact solutions.** Schwarzschild, Kerr (spinning), Reissner–Nordström (optional),
  Morris–Thorne/Ellis (wormholes). Each one gives an exact metric, Christoffel symbols and Riemann tensor in closed form.
- **Matter follows exact geodesics** in those metrics: timelike geodesics for entities and ships, null geodesics for light in shaders.
- **Multiple sources get superposed** in the weak-field region (this is approximate, and we document that). Near any one
  source, that source's exact metric takes over.
- **The metric changes slowly.** Mass, spin and charge update as matter is accreted, so the metric is "stationary per tick".
- **Binary black holes inspiral, merge and radiate gravitational waves** the way LIGO's waveform models do it:
  post-Newtonian dynamics with radiation reaction, a merger remnant from numerical-relativity fits, and a quasinormal-mode
  ringdown (§8b). This is the real physics, just not a live numerical-relativity solve.

### Units (the most important design decision)
1 block = 1 m, but the real c would make every effect invisible. **c is a per-dimension config value: 64 blocks/s by
default, 512 in planetoid space.** Horizon-scale effects (redshift, tidal forces, lensing, horizons) depend on r_s/r and not
on c. c sets how reachable special relativity is, how fast things orbit near a hole (c/2 at the ISCO), and how far a hole of
a given size pulls (its mass scales with c²). Wormholes between dimensions keep β, not the raw velocity.

- Elytra plus rockets, or fast Aeronautics ships, reach β ≈ 0.3–0.8, so γ becomes something you can see.
- BHs are defined by their mass M in Sable mass units (default block = 1.0). From there: `GM = G·M`,
  `r_s = 2GM/c²`, with G = c²/(2·10⁴) so that 10k default blocks collapse to r_s = 1 block.
- Check: r_s = 2, c = 64 → GM = 4096 b³/s². At 30 blocks, a = 4.5 b/s² (vanilla gravity is about 32 b/s²), so
  gravity is noticeable at range and lethal up close.
- Optional soft range cutoff (`gravityCutoffRadius`) so a large BH doesn't pull on the whole server.

---

## 1. Tech stack

| Concern | Choice |
|---|---|
| Loader / MC | NeoForge, MC 1.21.1 (the version Create 6 / Simulated / Aeronautics / Sable target) |
| Language | Kotlin 2.4 + **KotlinLangForge** (`modLoader="klf"`) |
| Build | Architectury Loom (NeoForge platform), Gradle 9.7 Kotlin DSL |
| Physics host | **Sable** (rigid-body sub-levels), **Create Simulated** API, **Create Aeronautics** |
| Rendering | **Veil** post-processing pipelines + custom GLSL; mixins for entity vertex warping |
| Math | Own small double-precision lib (Vec3d/Vec4d/Mat4d, tensors as flat DoubleArrays) |
| Library | **Archie** (Kernel Panic's lib): config with Cloth screens + server sync, `NetworkChannel` packets, NBT-backed saved state. Brings Architectury API + Cloth Config |
| Tests | JUnit 5 on a pure-JVM `physics-core` module, which has no Minecraft classes |
| Mixins | SpongePowered Mixin + MixinExtras |

### Gradle layout
```
create-relativity/
├─ physics-core/        # pure Kotlin: metrics, geodesics, tidal tensors, SR kinematics. Fully unit-tested.
├─ src/main/kotlin/net/kernelpanicsoft/relativity/
│  ├─ spacetime/        # sources registry, SavedData, spatial index, field sampling
│  ├─ dynamics/         # entity/ship geodesic integration, proper time, horizon logic
│  ├─ sable/            # Sable/Simulated integration (forces, tidal stress, splitting, teleport)
│  ├─ kinetics/         # Create kinetic time dilation
│  ├─ content/          # blocks, items, block entities, recipes, ponders
│  ├─ network/          # payloads (source state, proper-time sync)
│  └─ client/
│     ├─ render/        # post pipelines, uniform providers, env cubemap capture
│     ├─ deform/        # spaghettification / Lorentz warping VertexConsumer
│     └─ hud/           # proper-time clock, redshift overlay
└─ src/main/resources/assets/create_relativity/shaders/...
```

---

## 2. Physics core (`physics-core`, no MC dependency)

```kotlin
interface Metric {
    fun g(x: Vec4d, out: Mat4d)                         // g_{μν}
    fun christoffel(x: Vec4d, out: DoubleArray)         // Γ^λ_{μν}, 64 entries, closed form
    fun tidal(x: Vec4d, u: Vec4d, out: Mat3d)           // E_ij = R_{i0j0} in the local frame
    fun horizonRadius(): Double?
    fun staticRedshift(x: Vec4d): Double                // sqrt(-g_tt) where defined
}
```

Implementations:
- `Schwarzschild(M)` in **Kerr–Schild Cartesian coordinates**, so there's no coordinate singularity at the horizon and
  entities can actually cross it.
- `Kerr(M, a)` in Kerr–Schild coordinates: frame dragging, ergosphere, ISCO depending on prograde/retrograde orbit.
- `MorrisThorne(b0, Φ=0)` (Ellis drainhole): two asymptotic regions, used for wormholes.
- `Minkowski`, plus `WeakFieldSuperposition(sources)`: a 1PN metric for the far field of many sources.

Integrators:
- `GeodesicIntegrator`: adaptive RK45 (Dormand–Prince) in affine parameter, with step size limited by local curvature
  (`h ∝ r^1.5 / sqrt(M)`). It also tracks proper time τ.
- Fast path: when `r > 50 r_s`, use Newtonian + 1PN acceleration with no Christoffels. Most entities are here most of the time.

SR kinematics:
- Velocities are stored as **rapidity / 4-velocity**, not 3-velocity, so thrust can never push anything past c.
- `boost(β)`, velocity addition, aberration, Doppler factor `D = 1/(γ(1 − β·n))`.

### Tests (these define correctness)
- Photon sphere at 1.5 r_s, ISCO at 3 r_s (Schwarzschild); Kerr ISCO for a = 0.998 → ≈ r_g (prograde).
- Light deflection → 2r_s/b in the weak field.
- Perihelion precession of a test orbit matches `6πGM/(c²a(1−e²))`.
- Shapiro delay, gravitational redshift, and the muon-style time-dilation round trip (twin paradox: clocks disagree on reunion).
- Constants of motion E, L (and the Carter constant for Kerr) conserved to 1e-8 over 10⁵ steps.
- Wormhole: a radial geodesic passes through the throat at l = 0 and comes out in the other asymptotic region.

---

## 3. Spacetime world layer

- `GravSource` (sealed): `BlackHole(pos, M, J, Q)`, `WormholeMouth(pos, pairId, b0, otherDim, otherPos, orientation)`.
- `SpacetimeSavedData` for each level, plus a coarse **spatial hash** for "which sources affect this point".
- `SpacetimeField.sample(level, pos)`: returns the dominant metric, the redshift factor `α = dτ/dt` for a static observer,
  the acceleration, and the tidal tensor. This is the single entry point every other system uses.
- Server-authoritative. A `SourceStatePayload` syncs M, a, pos and the disk params to clients within render distance,
  with deltas sent at most every 2 ticks.
- Commands: `/relativity bh create <mass>`, `/relativity bh feed`, `/relativity wormhole link`, `/relativity c <value>`,
  `/relativity debug geodesics`.

---

## 4. Dynamics: entities and time

### Entity motion
- A mixin into `Entity.move` / `LivingEntity.travel` adds the geodesic acceleration (vanilla gravity is left alone outside
  the source's influence).
- Inside about 10 r_s, entity motion is taken over completely by `GeodesicIntegrator`, with sub-stepping per tick.
- **Event horizon**: once inside, every future-directed path leads to r → 0. Controls still work, but you can't escape.
  The singularity kills you (`create_relativity.singularity` damage type).

### Time dilation: the hard multiplayer problem
Every entity carries a **proper-time ledger** `τ` and a local rate `dτ/dt = α·sqrt(1 − v²_local/c²)` (the exact form comes from the metric).

- **Mobs, items, block entities, Create kinetics** use a *tick accumulator*: they run `floor(acc += dτ/dt)` ticks per
  world tick. Near a BH, furnaces smelt slower, crops grow slower, mobs move in slow motion, and Create shafts spin
  slower as seen from outside.
- **Players, singleplayer**: scale the server `TickRateManager` (vanilla `/tick rate` machinery) by `1/(dτ/dt)` of the
  player. Hover near the horizon and the world outside fast-forwards: sun racing, crops shooting up. That's the Interstellar
  "Miller's planet" moment.
- **Players, multiplayer**: you can't run the world at a different rate for each player. Their proper-time clock (HUD
  chronometer) diverges from world time, and the "aging" effects are personal: potion/effect durations, hunger and
  cooldowns run on τ. Config option: `multiplayerTimeDilation = LEDGER_ONLY | SLOW_PLAYER`.
- Chunk loading: a dilated region inside an otherwise-unloaded area gets a forced ticket for as long as a source is active
  (config cap on how many).

---

## 5. Black holes

### Creation (progression)
1. **Neutronium**: compress blocks through Create sequenced assembly (mechanical press → compacting under a
   "Gravitic Press" multiblock fed with huge stress units).
2. **Singularity Seed**: a Neutronium core in a containment field. Break the containment, or exceed the Chandrasekhar-like
   threshold, and it collapses into a BH with M = the mass that went in.
3. Creative/admin: the command plus a creative-only "Primordial Black Hole" item.

### Accretion and growth
- **Horizon eating**: blocks whose centers fall inside r_s are removed each tick, with a budget of N blocks per tick
  (config) to protect TPS. Their mass is added to M.
- **Disk feeding**: blocks within the tidal-disruption radius get ripped out as falling-block entities (or Sable
  fragments) and put on geodesics. They spiral in, briefly feed a visible accretion disk, then cross the horizon.
- **Angular momentum**: infalling matter brings L, which updates the spin `a = J/(Mc)` (capped at 0.998). A BH you feed
  from a spinning ship turns into a Kerr BH.
- **Energy budget**: radiative efficiency η(a) from the ISCO (5.7% for Schwarzschild → 32% for maximal Kerr) becomes
  disk luminosity → light level, heat damage, and fire spread near an actively feeding BH.
- **Hawking evaporation** (config, on by default): `dM/dt ∝ −1/M²`. Tiny BHs blow up (a scripted explosion scaled by the
  mass left), which keeps players from spawning micro-BHs everywhere. Big ones are effectively permanent.
- **Griefing guards**: gamerule `relativityBlackHoleGrowth`, a max-mass config, protected-block tag, claim-mod hooks.

### Exotic gameplay
- **Penrose process power plant**: throw items into the ergosphere on a Create launcher, catch the escaping half → net
  energy that becomes stress units. Draining it spins the BH down.
- **Gravitational slingshots** for Aeronautics ships.
- **Photon sphere**: light ("Lumen Beam" block) on the 1.5 r_s orbit can come back at you.

---

## 6. Spaghettification

This is the feature the whole project is built around, so it gets its own subsystem.

### Physics
- Tidal tensor from `Metric.tidal`. For Schwarzschild in the local frame: radial `+2GM/r³`, transverse `−GM/r³`.
- **Spaghettification onset** for a body of length L: `ΔA = 2GM L / r³ > threshold` (config, about 3× vanilla gravity
  for "uncomfortable", 20× for "lethal"). For small BHs this happens *outside* the horizon (good, you see it). Big BHs
  swallow you whole (also physically correct, and the Ponder scene explains why).
- Instead of tracking one point per entity, track **a chain of K material points** (feet → head, K ≈ 8). Each point
  follows its own geodesic, linked by springs that break past a stretch limit. Damage scales with strain rate.

### Rendering (`client/deform`)
- A `DeformingVertexConsumer` wraps the buffer for entities with an active tidal chain. Every vertex gets mapped from
  model-space height `y ∈ [0, h]` to a point along the **spline through the K chain points**, with transverse
  compression `1/sqrt(stretch)` (conserving volume).
- Vanilla entity models are big boxes, so a `TessellatingVertexConsumer` subdivides each quad N×N (adaptive: N=1 when
  unaffected, up to 16) to make the bending smooth. The result is a noodle that curves along the infall spiral.
- Also applied to: item entities, falling blocks, and **Sable sub-levels** (whole ship models get warped before they fracture).
- **First person**: the camera stays at the head point. Feet are at the far end of the chain, so you watch your own
  body stretch away into the disk. The FOV stretches radially, plus a chromatic smear.
- At the strain limit the chain breaks into segments that render as glowing ribbons, merge into the disk's particle
  stream, and redshift to black.
- Death message: "%s was spaghettified". Advancement: **"The Noodle Incident"**.

### Ships
Sable sub-levels get per-block tidal forces → internal stress. Past the material strength (configurable per block tag),
the sub-level **splits** along the weakest plane into fragments, which keep falling, deforming, and splitting again.

---

## 7. Special relativity

### Mechanics
- A global **speed limit c**. Entity and ship velocities are stored as 4-velocity, and thrust adds momentum
  `p = γmv`, so you approach c asymptotically and need more and more energy (stress) to get closer.
- **Relativistic momentum in collisions** (Sable impacts at high γ hit very hard: `KE = (γ−1)mc²`).
- **Time dilation from speed**: covered by the ledger (§4). Fly a fast ship round trip and the crew's chronometer reads
  less than the stay-at-home one. Twin paradox advancement: **"Younger Twin"**.
- **Length contraction**: a ship moving at β is physically contracted along its velocity in the world frame, which
  affects its collision bounds with the world (it can squeeze through gaps). This is a real gameplay effect, behind the
  config toggle `physicalContraction`.

### Visuals (post pipeline driven by camera 4-velocity)
- **Aberration**: remap view rays `cos θ' = (cos θ + β)/(1 + β cos θ)`. The world crowds toward the front.
- **Doppler shift**: convert RGB to an approximate spectral representation (3–5 Gaussian basis bins), shift by D, convert
  back. Blue ahead, red behind, IR/UV fade-out at the edges.
- **Searchlight effect**: intensity × D⁴ (tone-mapped).
- **Terrell–Penrose rotation vs contraction**: what you actually *see* of a fast object is rotated, not just squashed,
  because of light-travel time. Each moving entity or sub-level keeps a **position history ring buffer**, and the renderer
  draws it at its *retarded* position (solve `|x_cam − x(t_r)| = c (t − t_r)`). Contraction applied in the object's
  world-frame transform plus retarded-time rendering gives correct Terrell rotation without extra work.
- Toggle in config: `SEEN` (physically correct, the default) vs `MEASURED` (textbook contraction, for teaching).

---

## 8. Wormholes

- **Exotic Matter**: negative energy density, made in a **Casimir Cavity** multiblock (stacked plates, high RPM, liquid
  helium-ish coolant from a new fluid). The amount sets the throat radius `b0` and how stable it is.
- **Wormhole Mouths**: two linked anchor multiblocks. They can go in different dimensions. The throat geometry is
  Morris–Thorne, so geodesics near a mouth *curve into* the throat instead of teleporting at a plane.
- **Traversal**: an entity whose proper radial coordinate `l` crosses 0 gets moved to the other mouth with its velocity
  rotated by the mouth orientation transform. Sable sub-levels go through as a whole (needs a Sable teleport API, see
  risks). Tidal forces at the throat stay below human tolerance if b0 is big enough. If the throat is too small, you get
  spaghettified going through.
- **Stability**: the mouths use up exotic matter per tick, and more per unit mass passing through. If the supply runs
  out, the throat pinches shut (the Ellis → Schwarzschild collapse is scripted: anything inside ends up in a small BH. Fun.)
- **Time machine (stretch)**: move one mouth at relativistic speed and bring it back. Its proper time lags, so the mouths
  become **time-shifted**. Chronology protection: when a closed timelike curve would form, the throat blows up with a
  vacuum-fluctuation explosion (Hawking's conjecture as gameplay).
- **Rendering**: see §9.4.

---

## 8b. Binary black holes, mergers and gravitational waves

### Why not full numerical relativity
A single binary black hole merger simulation costs ~10⁵ CPU-hours. Gravitational-wave astronomy itself doesn't run NR live:
LIGO's template banks use **post-Newtonian (PN) inspiral + NR-calibrated merger/ringdown**. We do the same, so the dynamics,
the waveform, and the remnant are all physically grounded and testable against published results.

### Inspiral (`physics-core/binary`)
- Each BH is a point mass with spin. Equations of motion in harmonic coordinates: Newtonian + **1PN (EIH, N-body)** +
  **2.5PN radiation reaction** (Iyer–Will). Optional 1.5PN spin-orbit precession. Integrated at the physics substep.
- Orbits shrink because energy actually leaves as gravitational waves. Nothing is scripted, so eccentric orbits circularize,
  hyperbolic flybys emit a burst, and unbound pairs scatter.
- **Timescales work out at c = 64:** two r_s = 1 holes released 20 blocks apart merge in ~3 min (Peters formula, no fudge),
  and from 40 blocks in ~50 min. GW frequency at the ISCO is ~1.4 Hz, so you can watch it chirp.
- N > 2 BHs: EIH is N-body already. Radiation reaction is applied per pair (approximation, documented).

### Merger and remnant
- **Trigger:** separation drops below the binary's effective ISCO (~6 G M_total/c²), or the horizons overlap.
- **Final mass and spin** from NR fits (e.g. Jiménez-Forteza et al., Hofmann–Barausse–Rezzolla). Equal mass, no spin →
  a_f ≈ 0.686, with ~4.8% of the mass radiated.
- **Recoil kick** from the Lousto–Zlochower formula. Superkick configurations can fling the remnant off at ~1 block/s.
- The two sources are replaced by one Kerr hole with the remnant's mass, spin and kick velocity. Accretion disks merge
  into one debris ring.

### Ringdown
Dominant ℓ=m=2 quasinormal mode, with frequency and damping from Berti–Cardoso–Will fits in final spin, matched in
amplitude, phase and frequency to the end of the inspiral waveform.

### The waves themselves
- Strain h₊, h× from the **quadrupole formula applied to the integrated trajectories** during inspiral, stitched to the ringdown.
- Waves propagate outward at c: the observer at distance r sees `h(t − r/c)`. A source keeps a strain history ring buffer
  for retarded-time lookup.
- **Amplitude is visible in game units:** h ~ (r_s/r)(v/c)², so near merger at 50 blocks h ≈ 1–5%.
- **Physical effect:** geodesic deviation in TT gauge, `a_i = ½ ḧ_ij x^j`, applied per block to Sable sub-levels (ships flex
  and creak, weak joints can snap at merger) and as a small oscillating push on entities.
- **Visual:** a post pass reconstructs world position from depth and displaces it by the local strain, so the world ripples
  in the ₊/× quadrupole pattern as each wavefront sweeps past at c.
- **Audio:** the strain is sonified, pitch-shifted ×~200 into the audible band and streamed as a custom OpenAL audio stream.
  You hear the chirp.

### Content
- **Interferometer**: a Create multiblock with two perpendicular mirror arms and a laser. Comparator output follows
  the strain along its arms, and a display block plots the chirp. Advancements: "First Light", "Chirp".
- **GW energy harvesting** (stretch): a resonant-bar detector turns strain power into stress units.
- **BH + Neutronium-star mergers** (stretch): tidal disruption before merger, with a kilonova particle burst.

### Rendering a binary
The Schwarzschild LUT shortcut (§9.2) only works with spherical symmetry. A binary uses the full per-pixel ray marcher with
the superposed two-body metric (approximate), at half resolution. The shadow becomes two lobes and the Einstein rings tangle.

### Tests
- Circular-orbit decay time matches Peters (1964): `t = (5/256) c⁵ a⁴ / (G³ m₁ m₂ M)`.
- Radiated power matches the quadrupole formula, and the orbital energy loss matches the radiated GW energy.
- Eccentricity decays per Peters' `de/dt`.
- Remnant: equal mass, no spin → a_f ≈ 0.686, M_f ≈ 0.952 M.
- QNM (ℓ=m=2, n=0) at a = 0.686 → Mω ≈ 0.5527 − 0.0844i (geometric units).

## 9. Shaders

All of these are Veil post pipelines plus uniform providers, rendered in camera-relative coordinates to avoid float
precision trouble far from the origin.

### 9.1 Environment capture
For each visible source, render a **low-res cubemap** (128–256²) from the source position every N frames, round-robin
with at most 1 face per frame, with no entities and simplified chunks. Lensed rays that leave the screen region sample
this cubemap. Rays that land on-screen behind the BH sample the **scene color + depth** directly, which keeps full detail.

### 9.2 Black hole lensing (Schwarzschild fast path)
- Spherical symmetry means each ray stays in the plane through the camera, the BH, and the ray direction, so the problem
  reduces to the orbit equation `d²u/dφ² + u = (3/2) r_s u²`.
- **Precompute a 2D LUT** (impact parameter b × camera distance r) → (deflection angle, captured?, min radius) on the
  CPU with physics-core, and rebuild it only when r_s changes. Then the shader per pixel just does: find b → LUT lookup →
  rotate the ray in its plane → sample the environment. That's cheap enough for 1080p.
- What falls out of it for free: the shadow (radius `3√3/2 r_s`), Einstein ring, photon ring, and secondary images.

### 9.3 Accretion disk and Kerr
- Full per-pixel null-geodesic ray marching (RK4, 80–200 steps, early exit), half-res + bilateral upsample.
- The disk is a thin volumetric slab between the ISCO and about 20 r_s. Temperature follows the **Novikov–Thorne**
  profile and becomes blackbody color. Observed intensity ∝ `g⁴`, with `g` = gravitational × Doppler shift
  → the approaching side is brighter and bluer (the "lopsided" Interstellar-done-right look). Turbulence comes from
  animated 3D noise advected at Keplerian angular velocity.
- **Kerr**: the shadow shifts and flattens on one side, and frame dragging twists the disk image. It reuses the same
  ray marcher with the Kerr–Schild Christoffels ported to GLSL.
- Brightness drives a bloom pass and lens flare, and the HDR result is tone-mapped to match the rest of the scene.

### 9.4 Wormhole view
- The other mouth captures a cubemap from its side (same system as 9.1).
- The shader ray marches the Ellis metric. Rays that go through the throat sample the *far* cubemap, and rays that miss
  sample the local scene/cubemap. Result: a lensed spherical "window" showing the other place, with an Einstein ring of
  the near side around its rim.

### 9.5 SR post pass
Aberration + spectral Doppler + searchlight (§7), applied last, so lensing results get boosted correctly too.

### 9.6 Ambient effects
- Gravitational redshift tint + vignette that scale with `1 − α`.
- Time-dilation "world fast-forward" motion blur when the tick-rate scaling is active.
- Hawking evaporation: a hot white glow and a sparkle shell for micro-BHs right before they pop.

### 9.7 Compatibility
- Iris/shaderpacks own the pipeline. Detect Iris and either (a) inject through its post hooks if possible, or (b) fall
  back to a forward-rendered "billboard sphere" BH with a screen-space distortion pass. Needs testing early.
- Sodium/Embeddium: the entity deform wrapper is unaffected (entities aren't Sodium-batched). Verify that sub-level
  rendering goes through Sable's renderer and add the hook there.
- Quality presets: `LOW` (LUT lensing, no disk raymarch, no cubemap), `MEDIUM`, `HIGH`, `ULTRA` (full-res Kerr, 256³ noise).

---

## 9b. Planetoid world type

A `create_relativity:planetoid` world preset: an empty void dimension (Sable vacuum: `base_gravity` 0, `base_pressure` 0)
holding a star system whose planets are **Sable sub-levels in orbit**, with gravity supplied by the spacetime layer.

### Planets as curved cube-sphere grids
- **One planet = one Sable sub-level** (a single plot and rigid body). A single *seamless* square grid can't wrap a sphere:
  the sphere's Euler characteristic is 2, while an all-4-neighbor grid needs 0 (a torus). Every sphere grid has defects.
  Lat-long puts two at the poles, where cells collapse into slivers. The **cube-sphere** spreads eight mild ones over
  the cube corners (3 faces meet instead of 4) and keeps cells nearly uniform everywhere else. So the planet's grid is a
  cube-sphere laid out inside its one plot, with our code joining face edges.
- Each face region is a flat block grid in plot space, generated so its surface follows the sphere.
- **Face seams** are where the grid bends. Block-neighbor queries across a seam (fluids, redstone, Create shafts and belts,
  neighbor updates) are remapped to the adjacent face with the right rotation. That's the main engineering cost of the
  single-grid design, so the M14 spike prototypes it with redstone and a shaft line across a seam.
- Each face has its own **local down** (the face normal in plot coordinates). Physics and collision use the true voxels.
  A **curvature vertex warp** at render time bends each face's chunks onto the sphere (gnomonic cube→sphere map), so the
  surface looks round and the seams between faces disappear.
- Terrain is 3D noise sampled in sphere coordinates: core/mantle/crust layers, ores, caves, ocean shells to a sea radius,
  and biomes by latitude and altitude. Seeded from the world seed per planet.
- Generation is chunk by chunk inside the planet's plot, lazily, so a 128-radius planet doesn't stall world creation.

### Orbits
- The star and planets (and moons) are integrated as an **N-body system with the M10 integrator** (EIH 1PN). Same code as
  binary black holes, so orbits precess correctly near massive stars or a black hole.
- Planets are **kinematic** Sable bodies: their pose (orbit + axial spin) is set from the integrator every physics tick
  instead of letting Rapier integrate a body of millions of blocks. Ships and entities still collide with them normally.
- The orbital state lives in `SavedData` and advances even while a planet's plot is unloaded. Sable loading tickets
  keep nearby planets loaded.
- Day and night come from the planet's actual spin relative to the star. Seasons come from axial tilt.

### Gravity and standing on a planet
- Each planet is a gravitating source in the spacetime layer: weak-field (Newtonian + 1PN) outside, with uniform-density
  interior gravity below the surface.
- **Entity "up" follows the face you stand on**: the six cube-face directions, in the planet's local frame. Sable already
  carries entities in a sub-level's local frame, so on a face the gravity is axis-aligned there. Movement, collision,
  camera, and rendering need a gravity-direction layer (6 directions, smoothed camera roll on face transitions).
  This is the biggest mixin surface in the mod, so it gets its own spike first.

### Star and sky
- The star is a gravitating source rendered as an emissive sphere (limb darkening, granulation noise, corona). Sky light
  and block light follow the star direction. Heat damage up close.
- Starfield skybox, other planets visible as lit discs/spheres, and eclipses for free from geometry.

### Presets
- **Sol-like**: a star, 3–5 rocky planets, a moon or two.
- **Gargantua**: planets orbiting a spinning black hole instead of a star. The innermost one is a Miller's planet
  (tick-ledger time dilation from M7, disk lighting from M5/M9). This ties the whole mod together.

### Physics caveat (c and compactness)
At c = 64 a block planet with Minecraft-like surface gravity is absurdly compact: `GM/(Rc²) = gR/c²` ≈ 0.5 for
R = 100, g = 20 b/s². That's halfway to being a black hole. The planetoid dimension therefore gets its own **c = 512** (per-dimension c),
which gives compactness under 1%. Planets then behave like planets, while the star (or Gargantua) still shows strong-field
effects up close. Surface gravity and c are per-world config.

### Travel
Propellers and balloons don't work in vacuum, so interplanetary flight needs thrusters (the M11 Lorentz Drive, or
a simpler rocket thruster added here). Launch windows and Hohmann transfers fall out of the real orbits.

## 10. Create / Aeronautics integration

- **Kinetic time dilation**: a mixin on `KineticBlockEntity` speed / ticking scales the *visual and functional* RPM by the
  local `dτ/dt`. A contraption half inside a gravity well has stress mismatches between its parts, which become overstress.
  That's a real engineering puzzle.
- **Ships**: Sable sub-levels get geodesic accel on their center of mass, tidal torques (spin-orbit coupling), and
  per-block stress (§6). Propellers produce thrust as 4-force, so the relativistic limit applies.
- **New blocks**:
  - *Chronometer*: shows proper time, and a comparator output gives the τ−t difference.
  - *Redshift Goggles*: overlay with r_s, ISCO, photon sphere, and ergosphere outlines, plus orbital predictions (a
    geodesic preview line).
  - *Tidal Gauge*: redstone output proportional to tidal acceleration.
  - *Lorentz Drive*: an Aeronautics thruster that burns exotic matter/Neutronium to reach high β.
  - *Gravitic Press*, *Casimir Cavity*, *Containment Field Projector*, *Wormhole Anchor*.
- **Ponder scenes** for every machine plus "What is an event horizon", "Why big BHs don't spaghettify you",
  "Twin paradox".
- JEI/EMI recipes, advancements, and a Create-style tooltip summary.

---

## 11. Milestones

| # | Milestone | Deliverable / "done when" |
|---|---|---|
| **M0** ✅ | Scaffolding + **Sable API spike** | Mod loads with Create 6 + Aeronautics + Sable + Veil + KFF. [`docs/sable-spike.md`](docs/sable-spike.md) answers: how to apply force/torque to a sub-level, get mass/inertia, split a sub-level, teleport/move one across dimensions, and hook its rendering. |
| **M1** ✅ | physics-core | All §2 tests green. A CLI/plot harness draws orbits and precession. |
| **M2** ✅ | Spacetime layer + debug | `/relativity bh create`, synced sources, debug renderer for r_s / ISCO / photon sphere. |
| **M3** ✅ | **Lensing shader** (Schwarzschild LUT + cubemap) | Static BH looks right: shadow size, Einstein ring. *First visual wow point.* Known limits: the 360° capture (256²/face, from the nearest hole) smears where strongly magnified and its fog is centred on the hole; spin ignored until M9. |
| **M4** | Entity geodesics + horizon + proper time | Items and mobs orbit, precess, and fall in. Players die at the singularity. HUD chronometer. |
| **M5** | Accretion + growth + disk shader | BH eats blocks, grows, and the disk lights up and gets brighter while feeding. Hawking evaporation. |
| **M6** | **Spaghettification** | Tidal chains, deformation renderer, first-person stretch, death message. *Second wow point.* |
| **M7** | Time dilation systems | Tick accumulator for BEs/mobs/kinetics. Singleplayer tick-rate scaling. MP ledger mode. |
| **M8** | Sable/Aeronautics integration | Ships orbit, slingshot, get tidally ripped apart. Kinetic dilation overstress. |
| **M9** | Kerr | Spin from accretion, frame dragging, ergosphere, Kerr ray marcher, Penrose power plant. |
| **M10** | **Binary BHs + gravitational waves** | PN inspiral with radiation reaction, NR-fit remnant + kick, QNM ringdown, strain field, ship flexing, ripple shader, chirp audio, interferometer. *Third wow point.* |
| **M11** | Special relativity | Speed cap c, 4-momentum thrust, SR post pass, retarded-time rendering / Terrell rotation. |
| **M12** | Wormholes | Exotic matter, Casimir cavity, mouths, traversal (entities → ships), wormhole shader. |
| **M13** | Content and polish | Progression, recipes, Ponders, advancements, configs, Iris fallback, perf pass, MP testing. |
| **M14** | **Planetoid world type** | Spike (Sable plot size limits, lazy plot generation, entity gravity direction, cross-seam block adjacency). Cube-sphere generator + curvature warp, kinematic N-body orbits, face-relative gravity, star + sky, Sol-like and Gargantua presets, vacuum thrusters. *Finale.* |
| Stretch | | Wormhole time machines + chronology protection, Neutronium-star mergers, GW energy harvesting, charged BHs, space dimension with starfield. |

Order rationale: the visuals (M3) come early for motivation and to catch rendering-compat problems early. The Sable
spike (M0) comes first because it's the biggest unknown.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Sable/Simulated API is young or undocumented. Splitting or cross-dimension teleport of sub-levels may not be exposed | M0 spike. Contribute upstream or use mixins. Fall back to "disassemble → reassemble" for wormhole ship transit |
| Iris/shaderpack incompatibility | Early detection and fallback renderer (§9.7) |
| Per-entity geodesic cost | Weak-field fast path. Integrate fully only within 10 r_s. Sleep entities far from sources |
| Multiplayer time dilation is impossible to do "for real" | Ledger model plus singleplayer tick-rate scaling (§4) |
| World-eating BHs kill servers | Per-tick block budget, max mass, gamerule, protected tags, evaporation |
| Float precision in shaders at large coordinates | Camera-relative uniforms, double math on CPU only |
| Superposition of sources isn't real GR | Documented. The dominant source is exact near itself. Mergers use PN + NR fits, not a live NR solve |
| Planetoid: arbitrary entity gravity direction is a huge mixin surface; Sable plot size/lazy generation unknown | M14 starts with a spike. Gravity is limited to 6 cube-face directions per planet, not arbitrary vectors |
| Binary lensing cost (no spherical symmetry) | Half-res ray march, adaptive steps, LOW preset falls back to two independent LUT lenses |
| Scope | Milestones are shippable on their own: an M6 alpha (BHs + spaghetti) is already a fun mod |

---

## 13. Open decisions (defaults chosen; change if you disagree)
1. **c = 64 blocks/s** default, overridable per dimension (see 6).
2. **BH growth on by default** in survival, with evaporation keeping small ones in check.
3. **Multiplayer time dilation = LEDGER_ONLY** default.
4. **SR visuals = SEEN** (Terrell-correct) default.
5. Package `net.kernelpanicsoft.relativity`, mod id `create_relativity`.
6. ✅ **c is per dimension**: 64 by default, 512 in planetoid space so planets aren't near-black-holes (decided).
