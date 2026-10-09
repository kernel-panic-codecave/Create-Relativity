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
default, 512 in planetoid space.** (64 suits testing, where effects should be strong; for play the default should be
higher, the value to be chosen; tests keep 64.) Horizon-scale effects (redshift, tidal forces, lensing, horizons) depend on r_s/r and not
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
- **Relativistic mass** is how that shows in play, as in real physics: momentum is `γ m v` and force changes momentum,
  so a body's resistance to speeding up grows with γ (γ³ m along its motion, γ m across it): a thruster's push buys
  less and less speed toward c. Kinetic energy is `(γ − 1) m c²`, so a fast ship hits (and is hit) much harder than
  Newton says; ramming damage and anything that eats matter count it.
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
- **Sampled metric field (planned)**: the metric (or the light-bending acceleration) on a coarse 3D grid per chunk/region,
  computed off-thread and rebuilt only when sources change (they move or grow). Observer-independent, so it caches
  well. The GPU then follows light through the field instead of closed-form Schwarzschild/Kerr, so several holes, moving
  holes, gravity generators (§10) and other sources share one code path for lensing, physics and the debug view. The
  per-observer parts (path tables, image map, §9.2) stay as they are, but one general tracer and image map replace the
  Schwarzschild-table / Kerr-map split (and the seams and fallbacks between them).
  - **The full metric, not a potential**: lapse, shift and spatial metric per cell (3+1). A warp drive (§8a) is mostly
    shift (space flowing past), which a scalar potential can't hold.
  - **As built (physics-core `spacetime/field`)**: sources add into the *inverse* metric, `g^{μν} = η^{μν} + Σ δg^{μν}`,
    each with exact derivatives (`MetricSource`: `KerrSource` = Kerr exactly in Kerr–Schild form, `PointMass` of either
    sign in the weak field, `WarpBubble` = Alcubierre, moving). Light follows the Hamiltonian `½ g^{μν} p_μ p_ν`
    (`HamiltonianRay`), which needs nothing but `g^{μν}` and its gradient, the same form the GPU will use;
    `ComposedSpacetime` inverts numerically for clocks and timelike geodesics. Near one source the sum is that source
    exactly; between several it's superposition (exact in the weak field). Done: the game's `SpacetimeField` on it (one
    composed spacetime for motion and clocks); the post pass and the vertex lens's tables through every source.
  - **The per-section grid (`FieldGrid`, physics-core `SampledField`)**: sources that declare a reach are sampled per
    chunk section (`g^{μν}` and gradients, cubic Hermite along each axis between nodes, spacing finer near a source's
    core), built off-thread and rebuilt when they change; analytic until built and past their reach. Holes and moving
    sources (bubbles) stay analytic. Next: the GPU samples the same sections (a 3D texture atlas round the camera), and
    the gravity generator becomes the first source that only exists as a grid (its field from its blocks).
  - **No assumption that gravity attracts**: negative energy density (wormholes, warp bubbles) defocuses light, so images
    can shrink or vanish instead of multiplying. "Settled", "falls in", and the shadow, ring and fold handling are
    detected along each path, never assumed.
  - **Topology**: a path through a wormhole throat continues at the other mouth, possibly in another dimension; the
    tracer hands position and direction across, and the post pass sees the far side through that mouth's capture (§9.4).
    The vertex lens can't place what's seen through a throat, so that's the post pass's.
  - **Baked light maps per source region, not per chunk**: off-thread, from the metric field, a map for each region round
    a source (or merged sources) from a ray entering its boundary (2 for where, 2 for direction) to where it leaves or
    that it's captured. Rebuilt only when the source changes, not the camera, so it would remove the per-frame path
    tables, their catch-up lag and the redraws after a jump. Through a wormhole it says where a ray leaves the other mouth.
    - Symmetry sets the size: 1D for a non-spinning hole (deflection from impact parameter), 3D for a spinning one
      (~16 MB), full 4D only for an arbitrary or merged region. Per chunk would be ~100 MB each, and the interpolation
      error would add up chunk after chunk where bending is strongest.
    - Limits: it knows only boundary to boundary, so hitting geometry inside the region (the ground round a hole), or a
      camera inside it, still needs the path itself (or nested shells, each mapped to the next, at the price of error
      added per shell). The vertex lens needs the inverse (where a point shows on screen), so it keeps an image map;
      the baked map can only build that faster.
    - First prototype: a spinning-hole map used while the camera is outside the hole's region, timed against the current
      tables.
    - Measured (`CellLightMap`, 2026-10-08): per-cell 4D maps are accurate only where first-order bending already is
      (median 0.005 rad per cell at 3 r_s, 0.0001 at 24) and need ~1.6 GB for the band where they'd help: shelved.
  - **Baked lens tables per source type (instead)**: what the vertex lens inverts depends only on the camera relative to
    a lone source, so it's baked once per source shape and reused: Schwarzschild (the image table, done), the Ellis
    wormhole (spherical and scale-free like it, plus which side), Kerr (per spin, baked on first use and cached on disk;
    an image map per camera distance and height angle, interpolated between), an Alcubierre bubble (stationary in its
    own frame: per speed and wall sharpness). Baking runs off the frame budget, so its solves can be exhaustive (no
    images missed by a hurried search: the floor holes) and the per-frame tables and their lag go. Several sources
    together stay on the general per-frame tables.

### Spacetime debug view (F3)
- A new F3 toggle, next to F3+G's chunk borders: a **3D grid warped by spacetime** round every source (frame dragging
  shown for spinning holes).
- **Relative to an observer**: the player by default. Right-clicking an entity, ship or black hole while the view is on makes it
  the reference frame, and right-clicking nothing goes back to the player. Black holes get a clickable bounding box for this.
- **Geodesic projections**: each entity's and Sable ship's future free-fall path, drawn in the chosen observer's frame.

---

## 4. Dynamics: entities and time

### Entity motion
- A mixin into `Entity.move` / `LivingEntity.travel` adds the geodesic acceleration (vanilla gravity is left alone outside
  the source's influence).
- Inside about 10 r_s, entity motion is taken over completely by `GeodesicIntegrator`, with sub-stepping per tick.
- **Event horizon**: once inside, every future-directed path leads to r → 0. Controls still work, but you can't escape.
  The singularity kills you (`create_relativity.singularity` damage type).

### Time dilation: local tick rates from proper time
Proper time along a worldline is invariant: every observer agrees how much of it has passed. So **a thing's tick count
is its proper time**, and simulation stays consistent in multiplayer.

**Simulation (server).** Server time is coordinate time, the clock of an observer far from every hole, so
`dτ/dt ≤ 1` everywhere and regions only ever *skip* ticks.
- Each block entity, entity, chunk section and **Sable sub-level** keeps an accumulator: add dτ/dt (from its position
  and velocity, via `SpacetimeField.properTimeRate`) every server tick, run one real tick each time it reaches 1.
- A sub-level uses dτ/dt at its centre of mass, so the whole ship (Create kinetics, contraptions, block entities in
  its plot) runs on the ship's own clock. The twin paradox comes for free: a ship flown fast has smelted less on return.
- Hooks: entity ticks (`EntityTickEvent.Pre`, cancellable), block-entity ticking, random ticks per section, and
  scheduled block/fluid ticks (a due tick is re-deferred until its region's local clock has caught up).
- Rendering interpolates with a per-region partial tick, so dilated mobs and machines animate in smooth slow motion
  instead of stuttering.

**Presentation (client, each observer's frame).** At c = 64 b/s light delay is visible (640 blocks ≈ 10 s). The
server timestamps block changes, block-entity render state and entity positions. Each client applies them when their
light reaches it: `t_emit + distance/c`, with the extra delay from light passing near a hole (from the
physics-core photon paths). The result is a delayed view of distant events, pile-up in front of fast ships, and the
retarded positions M11's SR rendering needs. It's purely visual: interaction always uses server state. Toggleable.

**What a queue can't do.** It can only delay. A dilated observer should see the outside **sped up**, which in
multiplayer would mean showing events that haven't happened yet. So:
- **Multiplayer:** a dilated player can also tick at their local rate (`playerDilation = SLOW_PLAYER`): they move in
  slow motion and the outside looks fast *relative to them*, consistently for everyone. `LEDGER_ONLY` (only the HUD
  clock drifts, the player moves normally) remains as the gentler option.
- **Singleplayer:** the reference frame follows the player. The server tick rate becomes 20 / dτ/dt_player and every
  region ticks at its rate *relative to the player's*. Hovering near a hole, you play at normal speed while the world
  outside races (the Miller's-planet moment).
- Chunk loading: a dilated region in an otherwise unloaded area gets a forced ticket while a hole is active
  (config cap on how many).

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
- **Hawking evaporation** (config, on by default), from a game **Planck length** `l_P` (per-dimension config,
  default 1/64 block, barely visible). It fixes ħ through `l_P = √(ħG/c³)`, so temperature, luminosity and lifetime
  all follow from the real formulas: `dM/dt = −ħc⁴/(15360πG²M²)`, lifetime `t = 5120π·M³/(l_P²·c)` (M = r_s/2 in
  blocks). At c = 64, l_P = 1/64: r_s = 2 lasts ~12 days of real time, r_s = 0.2 ~17 min, r_s = 0.1 ~2 min.
  When **r_s reaches l_P** the hole is gone: a final burst of the remaining energy (light, heat, an explosion scaled
  by what's left) and the source is removed. Shrinking holes get hotter and brighter, as they should.
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

- **The fuels: two fluids.** *Degenerate Matter* (positive energy density) drives positive gravity: gravity generators
  pulling, artificial gravity. *Exotic Matter* (negative energy density) drives negative gravity: wormhole throats,
  warp bubble walls, repulsors. Both are consumed as they're used. They render like the end gateway's starfield:
  Degenerate Matter as the end gateway, Exotic Matter the same with the colours inverted. They're made in a **particle
  accelerator** (below), or at a lower rate in a **Casimir Cavity** for Exotic Matter (stacked plates, high RPM, a
  coolant fluid). For a wormhole, the Exotic Matter supplied sets the throat radius `b0` and how stable it is.
- **Matter recycler**: breaks any item (garbage included) down into **matter plasma**, at an efficiency per item like
  IC2's UU-matter recycler; the plasma is what the accelerator's injector takes in, and its collisions yield both fuels.
- **Particle accelerator**: a ring the player builds. The tube is glass walls threaded through **electromagnet rings**,
  each powered on its own (rotational power per ring), and it must hold a **vacuum**: the **injector** multiblock pumps
  the ring down using rotational energy, then injects matter. The ring's **size sets its efficiency** (a bigger ring,
  gentler bends, more energy per pass). Beams meet in a **collision cavity** multiblock, which collects the products:
  the two fluids, by what was injected and the collision energy. A leak (a broken wall, an unpowered magnet) loses the
  vacuum and the beam.
- **Wormhole Mouths**: two linked anchor multiblocks. They can go in different dimensions. The throat geometry is
  Morris–Thorne, so geodesics near a mouth *curve into* the throat instead of teleporting at a plane.
- **Traversal**: an entity whose proper radial coordinate `l` crosses 0 gets moved to the other mouth with its velocity
  rotated by the mouth orientation transform. Sable sub-levels go through as a whole (needs a Sable teleport API, see
  risks). Tidal forces at the throat stay below human tolerance if b0 is big enough. If the throat is too small, you get
  spaghettified going through.
- **Trains through wormholes**: Create already runs trains through Nether portals (portal tracks, with an API for other
  portal types). A wormhole mouth registers as one, so track laid into one mouth continues out of the other, across
  dimensions too; the train graph gets an edge through the throat. Riders see through the throat with the lens (§9.4),
  and the train feels the throat's tides and the mass passing through counts against stability.
- **Stability**: the mouths use up exotic matter per tick, and more per unit mass passing through. If the supply runs
  out, the throat pinches shut (the Ellis → Schwarzschild collapse is scripted: anything inside ends up in a small BH. Fun.)
- **Time machine (stretch)**: move one mouth at relativistic speed and bring it back. Its proper time lags, so the mouths
  become **time-shifted**. Chronology protection: when a closed timelike curve would form, the throat blows up with a
  vacuum-fluctuation explosion (Hawking's conjecture as gameplay).
- **Rendering**: see §9.4.

---

## 8a. Alcubierre warp drive

The other thing exotic matter is good for. The ship sits in a flat bubble that spacetime itself carries along:
`ds² = −dt² + (dx − v_s f(r_s) dt)² + dy² + dz²`, where `f` is a smooth top-hat shape function of the distance
`r_s` from the bubble centre (wall thickness σ, radius R).

### Physics (`physics-core/spacetime/Alcubierre`)
- An exact metric with closed-form Christoffels (same `Spacetime` interface as Kerr and Ellis). The bubble can
  move faster than the dimension's c relative to the outside, because nothing moves through space locally.
- **Tests:** a geodesic at the bubble centre rides along at v_s with **dτ = dt** (no time dilation, no
  acceleration felt inside), the interior is flat, and a free particle outside is left behind. Energy density in
  the wall is negative (the exotic-matter requirement), and the cost scales with v_s² and the bubble size.

### Gameplay
- **Warp Field Generator**: a Sable ship component that wraps the ship's bounding sphere in a bubble. Exotic matter
  is consumed by bubble size, wall thinness and speed, and the generator spins up and down over seconds.
- The ship moves **kinematically**: its Sable pose is advanced with the bubble, not pushed by forces. Crew and loose
  entities inside ride along untouched, and anything straddling the wall gets torn by the tidal shear.
- **Bubble extent**: a generator on a train extends its bubble to encompass the whole train, one on a ship (Sable)
  to encompass the whole ship, and a static one has a configurable extent.
- **Warp trains**: a Warp Field Generator on a Create train carriage. The train runs its track at superluminal speed,
  the bubble carried along the rails; the route is the course set before engaging, which suits the no-steering rule
  below. Stations make the stop points, and the arrival hazard (below) makes station design matter: arriving
  nose-first into a platform blasts it.
- **No steering from inside at superluminal speed**: the front wall is causally cut off from the crew (a horizon).
  Course and stop point are set before engaging. That's real physics, and it's a good gameplay constraint.
- **The arrival hazard** (McMonigal, Lewis & O'Byrne 2012): particles swept up at the front wall pile up as
  blueshifted energy and blast out ahead when the bubble stops. Arriving at a base nose-first damages it, scaled
  with trip length and speed.

### Visuals
- **In the ship blueprint view**: warp bubbles and gravity fields shown on the ship's blueprint (extent, wall,
  strength), so a builder sees what a generator covers before engaging it.
- From inside, starlight piles up ahead and is strongly blueshifted, the view behind goes dark and red, and the
  bubble wall shimmers. That's the lensing post pass driven by the Alcubierre metric (ray-marched, like Kerr in M9).
- From outside, the ship appears and vanishes abruptly, since its light reaches observers in a burst.

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

### 9.2 Black hole lensing: as built (M9 high-quality lens, now the only lens)
- **Vertex lens**: every world vertex moves to where its light appears, keeping its true distance so depth testing sorts
  occlusion. It's injected into vanilla core shaders and Sodium terrain (Veil), Flywheel instancing, and Iris shader-pack programs.
  - **Not spinning**: a precomputed image table over (camera distance, point distance, angle), built off-thread once.
  - **Spinning**: a per-frame **Kerr path table** (rays from the camera, followed through the Kerr metric, sampled along
    each path), inverted into a **Kerr image map**. The map is a 3D texture over points round the hole (log radius × polar angle × azimuth)
    holding how each image is turned from the straight line of sight. It's solved by Newton's method on the path table, warm-started
    from last frame, and each vertex reads it with one filtered lookup. Vertices at folds (where two images merge,
    magnification over 80×) and behind the hole are hidden.
- **Post pass**: draws the shadow and the disk, and fills what the vertex lens can't show (hidden vertices, the bent sky) by
  tracing bent paths through **block grids** that record what every block draws (vanilla, Sodium, BERs, Flywheel). One grid
  sits round the hole and one past it along the camera line. Traced hits end where the world's drawn chunks end, are coloured from the frame
  where the world drew that point, and are fogged like terrain. The sky comes from the frame, a sky probe, or a
  same-height search.
- **Performance target: 60 fps average on a laptop iGPU** (Intel Xe, 2256×1504). Measured by the `performance` client
  GameTest: frame rate split into the game, the vertex lens, the Kerr path pass, the image map and the post pass.
  - Baseline (2026-10-07): no hole about 250 fps. Vertex lens without spin about 140 fps. Kerr path pass about 23 ms.
    Image map 65–140 ms (being cut). Post pass 150–600 ms.
  - Plan: (1) Kerr image map instead of a per-vertex solve ✅, made cheap with warm starts and staggered retries.
    (2) Kerr path table cached by direction and rebuilt only when the camera moves relative to the hole.
    (3) The post pass reads precomputed paths instead of integrating per pixel.
    (4) The post pass traces at reduced resolution, and full-resolution pixels only look up and shade their hit.
- Still open: entities and Sable ships in the traced geometry; Distant Horizons LODs as far geometry; a learned colour
  transform so traced points match shader-pack shading; shader-pack shadows/sky/clouds/tonemapping.

### 9.2a Original plan: Schwarzschild fast path (superseded by 9.2)
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
  - *Particle Accelerator*: the multiblock making the two fuels, Degenerate and Exotic Matter (§8).
  - *Gravity Generator*: turns energy (Create rotational power: speed sets strength, stress pays for it) and fuel
    (Degenerate Matter for positive gravity, Exotic Matter for negative) into gravity.
    It warps spacetime in selectable shapes (a point mass, a flat artificial-gravity field, ...) through the sampled metric
    field (§3). It **collapses into a black hole** when the escape velocity at the edge of its warp reaches c, i.e. its
    Schwarzschild radius 2GM/c² outgrows the generator. At that point it really is a black hole, so an ordinary one replaces it.
- **Ponder scenes** for every machine plus "What is an event horizon", "Why big BHs don't spaghettify you",
  "Twin paradox".
- JEI/EMI recipes, advancements, and a Create-style tooltip summary.

---

## 10b. Space and the galaxy (long term)

Ambitious, after the core (M10+). Kept here so earlier work doesn't paint it into a corner.

- **Several client levels at once, as little invasive as possible**: chunk loading (the server's player ticket) carries
  through wormhole mouths into the other dimension, and the client holds that level too, to draw through the mouth and
  cross without a loading screen. Vanilla holds one client level; Immersive Portals does this deeply and breaks much.
  Aim: a second, read-only client level kept for drawing what's seen through a mouth or across a boundary, the
  fewest mixins that allow it, and a fallback (the mouth's captured view, §9.4) when another mod conflicts.
- **Seamless space**: each star's system is a space dimension; planets (the planetoid worlds, §9b) appear in it as
  space objects orbiting the star (N-body, §9b), and flying down to one or up from it is a level transition with no
  break (the multi-level support above).
- **A procedural galaxy** from the world seed: stars (type, mass, planets) generated on demand, each with its own space
  dimension, stitched by seamless transitions. Natural wormholes link some systems.
- **Ships**: Create + Sable ships for space and between stars. *Shuttles* small enough to land on planets; *motherships*
  too big to, that stay in space and carry shuttles (the Enterprise and its shuttles, a Star Wars frigate and the Falcon).
  Warp bubbles for interstellar trips (§8a), warp trains and wormhole railways (§8) for logistics.
- **Galaxy view and autopilot**: plot a course on a galaxy map; the ship flies itself, routing through natural and
  built wormholes or across empty space by warp, and the map shows the fuel it will burn (Degenerate / Exotic Matter).
- **Cockpits**: hologram monitors (the galaxy map, the ship's blueprint with its fields, instruments), levers and buttons
  as Create-style controls.
- **Warp drive feel**: sound design and animations for spin-up, engage, cruise and drop-out (the bubble forming, the
  view stretching), alongside the physical visuals (§8a).
- **Space stations**: Sable structures in orbit round a star or planet (the orbit integrator, §9b).
- **Endgame megastructures**: Dyson swarms and spheres (energy from a star), ring worlds; **planet cracking**: an
  orbital mining rig that strips a whole planet for its resources (Dead Space style).

## 11. Milestones

| # | Milestone | Deliverable / "done when" |
|---|---|---|
| **M0** ✅ | Scaffolding + **Sable API spike** | Mod loads with Create 6 + Aeronautics + Sable + Veil + KFF. [`docs/sable-spike.md`](docs/sable-spike.md) answers: how to apply force/torque to a sub-level, get mass/inertia, split a sub-level, teleport/move one across dimensions, and hook its rendering. |
| **M1** ✅ | physics-core | All §2 tests green. A CLI/plot harness draws orbits and precession. |
| **M2** ✅ | Spacetime layer + debug | `/relativity bh create`, synced sources, debug renderer for r_s / ISCO / photon sphere. |
| **M3** ✅ | **Lensing shader** (Schwarzschild LUT + cubemap) | Static BH looks right: shadow size, Einstein ring. *First visual wow point.* Known limits: the 360° capture (256²/face, from the nearest hole) smears where strongly magnified and its fog is centred on the hole; spin ignored until M9. |
| **M4** ✅ | Entity geodesics + horizon + proper time | Items and mobs orbit, precess, and fall in. Players die at the singularity. HUD chronometer. |
| **M5** ✅ | Accretion + growth + disk shader | BH eats blocks, grows, and the disk lights up and gets brighter while feeding. Hawking evaporation from a game Planck length; holes vanish in a final burst at r_s = l_P. Still open: Hawking glow on micro-holes; debris clumps feeding the disk's look. |
| **M6** ✅ | **Spaghettification** | Tide-tracking body stretch, noodle deformation renderer, ship tearing, death messages, advancement. *Second wow point.* Still open: first-person stretch; ships visibly stretching before they tear; glowing breakup at the end; spin-aware tides. |
| **M7** ✅ | Time dilation systems | Proper-time local tick rates for block entities, entities, random and scheduled ticks and Sable ships; per-block render clocks (game time, partial tick, Create animation, Flywheel visuals, GPU rotation); SP reference frame following the player; client light-travel delay. Still open: MP `SLOW_PLAYER`/`LEDGER_ONLY` (players currently ledger-only); speed-based dilation for entities (waits on M11 speed limit); extra delay for light passing near a hole; light delay for Sable ships, contraptions and sounds; forced chunk tickets for dilated regions. |
| **M8** | Sable/Aeronautics integration | Ships orbit, slingshot, get tidally ripped apart. Kinetic dilation overstress. |
| **M9** 🚧 | Kerr | **Done so far**: Kerr ray marcher; the high-quality vertex lens is the only lens, with spin (path table + image map), in vanilla/Sodium/Flywheel/Iris, with traced block grids for what it can't show (§9.2). **Open**: 60 fps on a laptop iGPU (§9.2 plan); entities and ships in the traced geometry; shader-pack fidelity; the F3 spacetime debug view (§3); geodesic picking; spin from accretion, ergosphere, Penrose plant. Spin from accretion, frame dragging, ergosphere, Kerr ray marcher, Penrose power plant. **High-quality lens preset**: lens nearby geometry in the **vertex shader** (each vertex moved by the bend for its own distance, so depth testing handles occlusion and uncovering, with no near/far cutoff and no doubling), injected into world shaders via Veil (vanilla, Sodium terrain, Flywheel, entities), with a widened culling frustum. The far background, shadow and Einstein ring stay on the post pass. Ray-marching the frame + capture depth is the fallback; see the parked `lens-experiments` branch for the screen-space attempts. **Geodesic picking**: mixin the crosshair `clip` (and projectile raycasts) to march the null geodesic, so you click what you see bent around a hole. |
| **M10** | **Binary BHs + gravitational waves** | First the sampled metric field (§3), so two holes lens and pull through one path. PN inspiral with radiation reaction, NR-fit remnant + kick, QNM ringdown, strain field, ship flexing, ripple shader, chirp audio, interferometer. *Third wow point.* |
| **M11** | Special relativity | Speed cap c, 4-momentum thrust, SR post pass, retarded-time rendering / Terrell rotation. |
| **M12** | Wormholes + warp drive | Gravity Generator (§10) on the metric field, collapsing into a hole at v_esc = c. Exotic matter, Casimir cavity, mouths, traversal (entities → ships), wormhole shader. **Alcubierre warp drive** (§8a): metric + tests, Warp Field Generator, kinematic bubble travel, arrival blast, warp view shader. |
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
| Lens cost on integrated GPUs | Per-frame work moved into cached tables (image map, direction-indexed path table); post pass at reduced resolution; measured by the `performance` GameTest against a 60 fps target |
| Scope | Milestones are shippable on their own: an M6 alpha (BHs + spaghetti) is already a fun mod |

---

## 13. Open decisions (defaults chosen; change if you disagree)
1. **c = 64 blocks/s** default, overridable per dimension (see 6).
2. **BH growth on by default** in survival, with evaporation keeping small ones in check.
3. **Multiplayer player dilation = LEDGER_ONLY** default (`SLOW_PLAYER` opt-in); everything else always ticks on its proper time.
4. **SR visuals = SEEN** (Terrell-correct) default.
5. Package `net.kernelpanicsoft.relativity`, mod id `create_relativity`.
6. ✅ **c is per dimension**: 64 by default, 512 in planetoid space so planets aren't near-black-holes (decided).
