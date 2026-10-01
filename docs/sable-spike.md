# Sable API spike (M0)

Sable 2.0.5 / Create Aeronautics 1.3.2, read from source (github.com/ryanhcode/sable, PolyForm Shield, so depend on it but don't copy code).

## Applying forces to sub-levels: **supported**
- Subscribe to `ForgeSablePrePhysicsTickEvent` on `NeoForge.EVENT_BUS`. It fires once per physics **substep** with `timeStep`,
  after the built-in lift/drag and **before** `ServerSubLevel.applyQueuedForces`. So anything queued in the handler gets applied the same substep.
- `serverSubLevel.getOrCreateQueuedForceGroup(group).applyAndRecordPointForce(localPoint, localImpulse)`.
  - Both arguments are in **plot-local** coordinates. Convert world vectors with `logicalPose().transformNormalInverse(v)`.
  - The second argument is an **impulse** (force × timeStep), not a force.
  - Per-point application gives torque for free, so tidal forces can be applied as one impulse per block cluster.
- Custom `ForceGroup`s are registered into `ForceGroups.REGISTRY_KEY` (Sable creates the registry) and show up in Sable's force debug view.
  **Don't use Veil's `RegistrationProvider` from this mod.** On NeoForge it casts the caller's container to `FMLModContainer`
  and throws for KotlinLangForge's `KotlinModContainer`. Use a plain `DeferredRegister` on `dev.nyon.klf.MOD_BUS`.
- Reference implementation: Simulated's `EndSeaPhysics` (a world-space field acting on every sub-level).
- Implemented in `sable/SpikeAttractor.kt`. Test it with `/relativity spike attractor <pos> <r_s>`.

## Mass and inertia: **supported**
`ServerSubLevel.getMassTracker()` gives `MassData`: mass, inverse mass, inertia tensor, and center of mass (local; `null` when invalid).
Default block mass is 1.0 (`sable:mass` block property, datapack-overridable).

## Velocity / speed-of-light cap: **supported**
`RigidBodyHandle` (`physicsSystem.getPhysicsHandle(subLevel)`): `getLinearVelocity`, `getAngularVelocity`, `addLinearAndAngularVelocity`.
The relativistic cap can be enforced in the post-physics event by rescaling the velocity.

## Gravity: **per-dimension only**
`DimensionPhysicsData.getGravity(level, pos)` ignores `pos` and returns a uniform `base_gravity` (default 11 m/s² down),
which gets passed to the native Rapier pipeline at init. Black hole gravity has to be **added as queued forces**.
It can't replace the base gravity locally. A "space" dimension can zero out `base_gravity` through a datapack (`dimension_physics/*.json`).

## Splitting: **automatic**
`SubLevelHeatMapManager` flood-fills after block changes and splits disconnected regions into new sub-levels.
**Tidal disruption = remove/break the weakest connecting blocks, and Sable does the rest.** There's no public "split along plane" call, and we don't need one.

## Teleport: **same-level only**
`RigidBodyHandle.teleport(position, orientation)` works within a level.
**There's no cross-dimension sub-level transfer.** Wormholes to another dimension need a fallback: disassemble (record the blocks/BEs),
remove, and `SubLevelAssemblyHelper.assembleBlocks` in the target level, plus entity transfer. Same-dimension wormholes can use `teleport` directly.

## Rendering hooks: **not yet investigated**
Sable has its own sub-level renderer (`render/`) and Sodium/Iris compat. The deformation hook for spaghettified ships is still open (needed by M6).

## Dependency coordinates (verified)
| Artifact | Repo |
|---|---|
| `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5` | maven.ryanhcode.dev/releases |
| `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0` (Pose3d, math) | maven.ryanhcode.dev/releases |
| `foundry.veil:veil-neoforge-1.21.1:4.3.2` | maven.blamejared.com |
| `dev.simulated_team.simulated:simulated-neoforge-1.21.1:1.3.1` | maven.ryanhcode.dev/releases |
| `dev.eriksonn.aeronautics:aeronautics-neoforge-1.21.1:1.3.1` | maven.ryanhcode.dev/releases |
| `com.tterrag.registrate:Registrate:MC1.21-1.3.0+67` | maven.ithundxr.dev/**snapshots** |
| `dev.ryanhcode.sable:sable-sable_rapier-1.21.1:2.0.5` (natives, runtime) | maven.ryanhcode.dev/releases |
| `gg.moonflower:molang-compiler:3.1.1.19`, `io.github.ocelot:glsl-processor:0.2.3` (Veil runtime libs) | maven.blamejared.com |

The Maven Sable jar doesn't jar-in-jar its dependencies (unlike the Modrinth release), so companion, rapier and Veil's libraries are declared explicitly.

**Don't use the Modrinth `create-aeronautics` "bundled" jar in dev.** Loom's remap drops `META-INF/jarjar/metadata.json`, so only the empty
`aeronautics_bundled` wrapper loads and Simulated/Aeronautics/Offroad silently go missing.
