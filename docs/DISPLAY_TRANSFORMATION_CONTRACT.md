# Display transformation contract

`displaytransformation`, `settransformation` and `transformation` map to
`display-transformation` in direct skills and imported configuration. The
inspected native reference is public MythicMobs 5.7.2
`DisplayTransformationMechanic`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.

## Arguments and operation

| Field | Alias priority | Default |
| --- | --- | --- |
| Operation | `action`, `a` | `SET` |
| Component | `transformationtype`, `transformation`, `type`, `tt` | `TRANSLATION` |
| Numeric components | `value`, `val` | `0,0,0` |

The last occurrence of the same key wins, while alias priority is independent
of declaration order. Unknown enum values fall back to the native default.
Operations are `SET`, `ADD`, `MULTIPLY` and `DIVIDE`. Components are
`TRANSLATION`, `SCALE`, `LEFT_ROTATION` and `RIGHT_ROTATION`. Translation and
scale consume three floats; rotation consumes four floats in x,y,z,w order.
Extra components are resolved but only the required prefix is applied.

Vector multiplication/division is component-wise. Quaternion multiplication
uses current rotation multiplied by the supplied quaternion. Division multiplies
the current quaternion by the inverse of the supplied quaternion. Rotation
addition is component-wise; neither native nor this implementation normalizes
the result. The other three transformation components remain unchanged.

The targeted form requires a Display entity. The no-target form acts on the
caster Display, even when skill metadata contains an inherited living target.
Explicit location selection is inert. Dynamic numbers resolve in the selected
Display's context. Generic direct selectors such as `@self` and `@trigger`
retain nonliving recipients; general nonliving radius/chain selection and
nonliving target conditions retain the limitations in
[ENTITY_SETTER_CONTRACT.md](ENTITY_SETTER_CONTRACT.md).

After preparing the new transformation, the mechanic calls
`setInterpolationDelay(getInterpolationDelay())` before `setTransformation`.
Re-setting that same delay restarts interpolation on the client. It preserves
the interpolation duration and teleport duration. It does not set those durations
from additional mechanic arguments.

## Explicit implementation differences

Helstera resolves quoted vectors and dynamic finite values itself. The public
native float parser gates those forms behind Premium and falls back to one.
For `value="4,5,6"`, native splits before quote handling and obtains `[1,5,1]`;
Helstera obtains `[4,5,6]`. A target Y placeholder resolves to 170 in the fixture,
while the public native result is one. Malformed ordinary text falls back to
one. Native float range syntax is supported, including fixed ranges and its
float sampling arithmetic; randomized distributions still need live acceptance.

Insufficient components reject binding/import before mutation. Native instead
throws during casting. Explicit NaN/infinite components reject binding, and
non-finite resolved values or transformation results reject before either
interpolation or transformation writes. This includes division by zero and
float overflow. These are finite-state protections, not claims of native
invalid-input parity. Unsupported imported lines remain available in the
`unsupported` source list. Unknown outer selectors and trailing positional
arguments also reject instead of becoming an inert active action.

## Evidence and remaining work

The [2026-11-26 r1 report](../tools/paper-integration/reports/paper-1.21.1-display-transform-20261126-r1.json)
passes 87 scoped observations: 29 scenarios through native/direct/imported
paths. It covers all sixteen operation/component combinations, block/item/text
Displays, aliases and priorities, dynamic and quoted parser differences,
malformed numbers, fixed ranges, extra components, no-target caster selection,
wrong-entity rejection and short-vector rejection.

Two protocol clients observe a selected Display and an untouched control.
The independent auditor computes vector/quaternion results independently,
reconstructs initial/final metadata from actual packets, and verifies the delay
restart and preserved duration settings. It checks archive, source and artifact
hashes. This proves the scoped data sent to clients; it does not prove human
visual interpolation or rendered orientation.
All 3,842 rehashed semantic mutations are rejected by the independent auditor.

The artifact passes 2,928 Maven tests and all 11 SpotBugs checks. Plugin SHA-256:
`d8ca14a7aa255ecf30d240f393c4dae9f7f62cccc25c6dbcc31df14d6e8279b6`.
The initial offline attempt failed because the new Mockito test nested a second
mock call inside unfinished stubbing; the corrected test setup passes. No live
failure was hidden by that test correction.

Remaining acceptance includes actual client rendering across interpolation
frames, randomized ranges, non-finite native controls, repeated/delayed casts,
reload and reconnect, broader nonliving selectors/conditions, full native
dispatcher scheduling and the final release gate. Earlier focused reports
retain their original artifacts and do not certify this newer JAR.
